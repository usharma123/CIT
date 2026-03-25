package com.cit.clsnet.service;

import com.cit.clsnet.config.ClsNetProperties;
import com.cit.clsnet.model.QueueMessage;
import com.cit.clsnet.model.QueueName;
import com.cit.clsnet.model.Trade;
import com.cit.clsnet.model.TradeStatus;
import com.cit.clsnet.repository.TradeRepository;
import com.cit.clsnet.xml.FpmlTradeMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class TradeIngestionService {

    private static final Logger log = LoggerFactory.getLogger(TradeIngestionService.class);

    private final QueueBroker queueBroker;
    private final TradeRepository tradeRepository;
    private final TransactionTemplate transactionTemplate;
    private final CurrencyValidationService currencyValidationService;
    private final QueueMessageTracing queueMessageTracing;
    private final FailureClassifier failureClassifier;
    private final TradeIngestionService self;
    private final ExecutorService executor;
    private final int threadCount;
    private final XmlMapper xmlMapper;
    private final ObjectMapper jsonMapper;
    private volatile boolean running = true;

    public TradeIngestionService(
            @Qualifier("ingestionExecutor") ExecutorService executor,
            QueueBroker queueBroker,
            TradeRepository tradeRepository,
            TransactionTemplate transactionTemplate,
            CurrencyValidationService currencyValidationService,
            QueueMessageTracing queueMessageTracing,
            FailureClassifier failureClassifier,
            @Lazy TradeIngestionService self,
            ClsNetProperties properties) {
        this.queueBroker = queueBroker;
        this.executor = executor;
        this.tradeRepository = tradeRepository;
        this.transactionTemplate = transactionTemplate;
        this.currencyValidationService = currencyValidationService;
        this.queueMessageTracing = queueMessageTracing;
        this.failureClassifier = failureClassifier;
        this.self = self;
        this.threadCount = properties.getThreads().getIngestion();
        this.xmlMapper = new XmlMapper();
        this.jsonMapper = new ObjectMapper();
    }

    @PostConstruct
    public void startConsumers() {
        for (int i = 0; i < threadCount; i++) {
            executor.submit(this::processLoop);
        }
        log.info("Trade Ingestion Service started with {} consumer threads", threadCount);
    }

    @PreDestroy
    public void stopConsumers() {
        running = false;
        executor.shutdownNow();
    }

    private void processLoop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                QueueMessage message = queueBroker.claimNext(QueueName.INGESTION, Thread.currentThread().getName())
                        .orElse(null);
                if (message == null) {
                    sleepForPollInterval();
                    continue;
                }

                Span processingSpan = queueMessageTracing.startProcessingSpan(message);
                try (Scope ignored = processingSpan.makeCurrent()) {
                    try {
                        IngestionOutcome outcome = self.processTradeXml(message.getPayload());
                        queueBroker.complete(message);
                        queueMessageTracing.markOutcome(processingSpan, outcome == IngestionOutcome.REJECTED ? "rejected" : "completed");
                    } catch (QueueProcessingException e) {
                        processingSpan.recordException(e);
                        FailureContext failureContext = e.getFailureContext();
                        QueueFailureDisposition disposition = queueBroker.fail(message, failureContext);
                        queueMessageTracing.markFailure(processingSpan, failureContext, disposition);
                    } catch (Exception e) {
                        processingSpan.recordException(e);
                        FailureContext failureContext = failureClassifier.classify(
                                e,
                                FailureReason.PROCESSING_ERROR,
                                "Failed to process trade XML");
                        QueueFailureDisposition disposition = queueBroker.fail(message, failureContext);
                        queueMessageTracing.markFailure(processingSpan, failureContext, disposition);
                    }
                } finally {
                    processingSpan.end();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error processing trade XML", e);
            }
        }
    }

    public IngestionOutcome processTradeXml(String xml) {
        ParsedTradeMessage parsedTrade = parseTradeMessage(xml);
        ValidationResult validationResult = validate(parsedTrade);

        if (!validationResult.valid()) {
            if (!validationResult.persistRejected()) {
                throw new QueueProcessingException(validationResult.message(), validationResult.reason(), false);
            }

            transactionTemplate.executeWithoutResult(status -> {
                Trade rejected = mapToTrade(parsedTrade);
                rejected.setStatus(TradeStatus.REJECTED);
                tradeRepository.save(rejected);
            });

            Span.current().setAttribute("trade.outcome", "rejected");
            Span.current().setAttribute("trade.status", TradeStatus.REJECTED.name());
            Span.current().setAttribute("rejection.reason", validationResult.reason().code());
            log.warn("Trade {} rejected - {}", parsedTrade.tradeId(), validationResult.reason().code());
            return IngestionOutcome.REJECTED;
        }

        String[] outMessage = {null};
        transactionTemplate.executeWithoutResult(status -> {
            Trade trade = mapToTrade(parsedTrade);
            trade.setStatus(TradeStatus.VALIDATED);
            trade = tradeRepository.save(trade);
            log.debug("Trade {} persisted with id={}, status=VALIDATED",
                    trade.getTradeId(), trade.getId());
            outMessage[0] = String.format("{\"tradeId\": %d}", trade.getId());
        });

        if (outMessage[0] != null) {
            queueBroker.publish(QueueName.MATCHING, outMessage[0]);
        }
        return IngestionOutcome.COMPLETED;
    }

    private ParsedTradeMessage parseTradeMessage(String xml) {
        try {
            FpmlTradeMessage message = xmlMapper.readValue(xml, FpmlTradeMessage.class);
            String json = jsonMapper.writeValueAsString(message);
            FpmlTradeMessage.FpmlTrade trade = message.getTrade();
            String valueDateText = trade == null ? null : normalizeString(trade.getValueDate());
            LocalDate valueDate = null;
            if (valueDateText != null) {
                try {
                    valueDate = LocalDate.parse(valueDateText);
                } catch (DateTimeParseException e) {
                    throw new QueueProcessingException("Trade valueDate is invalid", e, FailureReason.INVALID_VALUE_DATE, false);
                }
            }

            return new ParsedTradeMessage(
                    json,
                    normalizeString(message.getHeader() == null ? null : message.getHeader().getMessageId()),
                    normalizeString(trade == null ? null : trade.getTradeId()),
                    normalizeString(trade == null ? null : trade.getTradeType()),
                    normalizeString(trade == null || trade.getParty1() == null ? null : trade.getParty1().getPartyId()),
                    normalizeString(trade == null || trade.getParty2() == null ? null : trade.getParty2().getPartyId()),
                    normalizeString(trade == null || trade.getParty1() == null ? null : trade.getParty1().getRole()),
                    normalizeString(trade == null || trade.getParty2() == null ? null : trade.getParty2().getRole()),
                    normalizeCurrency(trade == null || trade.getCurrencyPair() == null ? null : trade.getCurrencyPair().getCurrency1()),
                    trade == null || trade.getCurrencyPair() == null ? null : trade.getCurrencyPair().getAmount1(),
                    normalizeCurrency(trade == null || trade.getCurrencyPair() == null ? null : trade.getCurrencyPair().getCurrency2()),
                    trade == null || trade.getCurrencyPair() == null ? null : trade.getCurrencyPair().getAmount2(),
                    trade == null || trade.getCurrencyPair() == null ? null : trade.getCurrencyPair().getExchangeRate(),
                    valueDate);
        } catch (QueueProcessingException e) {
            throw e;
        } catch (Exception e) {
            throw new QueueProcessingException("Failed to parse trade XML", e, FailureReason.INVALID_XML, false);
        }
    }

    private ValidationResult validate(ParsedTradeMessage trade) {
        if (trade.tradeId() == null) {
            return ValidationResult.terminal(FailureReason.MISSING_TRADE_ID, "Trade missing tradeId");
        }
        if (trade.counterparty1() == null || trade.counterparty2() == null) {
            return ValidationResult.terminal(FailureReason.MISSING_PARTY, "Trade missing party information");
        }
        if (trade.currency1() == null || trade.currency2() == null) {
            return ValidationResult.terminal(FailureReason.MISSING_CURRENCY, "Trade missing currency information");
        }
        if (trade.amount1() == null || trade.amount2() == null) {
            return ValidationResult.terminal(FailureReason.MISSING_AMOUNT, "Trade missing amount information");
        }
        if (trade.valueDate() == null) {
            return ValidationResult.terminal(FailureReason.INVALID_VALUE_DATE, "Trade valueDate is invalid");
        }
        if (!currencyValidationService.isSupported(trade.currency1()) || !currencyValidationService.isSupported(trade.currency2())) {
            return ValidationResult.softReject(FailureReason.UNSUPPORTED_CURRENCY, "Trade uses an unsupported currency");
        }
        if (trade.amount1().compareTo(BigDecimal.ZERO) <= 0 || trade.amount2().compareTo(BigDecimal.ZERO) <= 0) {
            return ValidationResult.softReject(FailureReason.INVALID_AMOUNT, "Trade amount must be positive");
        }
        return ValidationResult.success();
    }

    private Trade mapToTrade(ParsedTradeMessage parsedTrade) {
        Trade trade = new Trade();
        trade.setTradeId(parsedTrade.tradeId());
        trade.setMessageId(parsedTrade.messageId());
        trade.setCounterparty1(parsedTrade.counterparty1());
        trade.setCounterparty2(parsedTrade.counterparty2());
        trade.setRole1(parsedTrade.role1());
        trade.setRole2(parsedTrade.role2());
        trade.setCurrency1(parsedTrade.currency1());
        trade.setCurrency2(parsedTrade.currency2());
        trade.setAmount1(parsedTrade.amount1());
        trade.setAmount2(parsedTrade.amount2());
        trade.setExchangeRate(parsedTrade.exchangeRate());
        trade.setValueDate(parsedTrade.valueDate());
        trade.setTradeType(parsedTrade.tradeType());
        trade.setRawJson(parsedTrade.rawJson());
        trade.setReceivedAt(Instant.now());
        return trade;
    }

    private String normalizeString(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String normalizeCurrency(String value) {
        String normalized = normalizeString(value);
        return normalized == null ? null : normalized.toUpperCase();
    }

    private void sleepForPollInterval() throws InterruptedException {
        TimeUnit.MILLISECONDS.sleep(queueBroker.getPollInterval().toMillis());
    }

    public enum IngestionOutcome {
        COMPLETED,
        REJECTED
    }

    private record ParsedTradeMessage(
            String rawJson,
            String messageId,
            String tradeId,
            String tradeType,
            String counterparty1,
            String counterparty2,
            String role1,
            String role2,
            String currency1,
            BigDecimal amount1,
            String currency2,
            BigDecimal amount2,
            BigDecimal exchangeRate,
            LocalDate valueDate) {
    }

    private record ValidationResult(boolean valid, boolean persistRejected, FailureReason reason, String message) {
        static ValidationResult success() {
            return new ValidationResult(true, false, null, null);
        }

        static ValidationResult softReject(FailureReason reason, String message) {
            return new ValidationResult(false, true, reason, message);
        }

        static ValidationResult terminal(FailureReason reason, String message) {
            return new ValidationResult(false, false, reason, message);
        }
    }
}

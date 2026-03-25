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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class TradeIngestionService {

    private static final Logger log = LoggerFactory.getLogger(TradeIngestionService.class);

    private final QueueBroker queueBroker;
    private final TradeRepository tradeRepository;
    private final TransactionTemplate transactionTemplate;
    private final CurrencyValidationService currencyValidationService;
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
            @Lazy TradeIngestionService self,
            ClsNetProperties properties) {
        this.queueBroker = queueBroker;
        this.executor = executor;
        this.tradeRepository = tradeRepository;
        this.transactionTemplate = transactionTemplate;
        this.currencyValidationService = currencyValidationService;
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

                try {
                    self.processTradeXml(message.getPayload());
                    queueBroker.complete(message);
                } catch (QueueProcessingException e) {
                    queueBroker.fail(message, e.getMessage(), e.isRetryable());
                } catch (Exception e) {
                    queueBroker.fail(message, e.getMessage(), false);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error processing trade XML", e);
            }
        }
    }

    public void processTradeXml(String xml) {
        String[] outMessage = {null};

        transactionTemplate.executeWithoutResult(status -> {
            try {
                FpmlTradeMessage message = xmlMapper.readValue(xml, FpmlTradeMessage.class);

                String json = jsonMapper.writeValueAsString(message);

                FpmlTradeMessage.FpmlTrade fpmlTrade = message.getTrade();
                if (!isValid(fpmlTrade)) {
                    Trade rejected = mapToTrade(message, json);
                    rejected.setStatus(TradeStatus.REJECTED);
                    tradeRepository.save(rejected);
                    log.warn("Trade {} rejected - validation failed", fpmlTrade.getTradeId());
                    return;
                }

                Trade trade = mapToTrade(message, json);
                trade.setStatus(TradeStatus.VALIDATED);
                trade = tradeRepository.save(trade);
                log.debug("Trade {} persisted with id={}, status=VALIDATED",
                        trade.getTradeId(), trade.getId());

                outMessage[0] = String.format("{\"tradeId\": %d}", trade.getId());

            } catch (Exception e) {
                throw new QueueProcessingException("Failed to process trade XML", e, false);
            }
        });

        if (outMessage[0] != null) {
            queueBroker.publish(QueueName.MATCHING, outMessage[0]);
        }
    }

    private boolean isValid(FpmlTradeMessage.FpmlTrade trade) {
        if (trade == null) return false;
        if (trade.getTradeId() == null || trade.getTradeId().isBlank()) return false;
        if (trade.getParty1() == null || trade.getParty1().getPartyId() == null) return false;
        if (trade.getParty2() == null || trade.getParty2().getPartyId() == null) return false;
        if (trade.getCurrencyPair() == null) return false;
        if (!currencyValidationService.isSupported(trade.getCurrencyPair().getCurrency1())) return false;
        if (!currencyValidationService.isSupported(trade.getCurrencyPair().getCurrency2())) return false;
        if (trade.getCurrencyPair().getAmount1() == null ||
            trade.getCurrencyPair().getAmount1().compareTo(BigDecimal.ZERO) <= 0) return false;
        if (trade.getCurrencyPair().getAmount2() == null ||
            trade.getCurrencyPair().getAmount2().compareTo(BigDecimal.ZERO) <= 0) return false;
        if (trade.getValueDate() == null) return false;
        return true;
    }

    private Trade mapToTrade(FpmlTradeMessage message, String rawJson) {
        FpmlTradeMessage.FpmlTrade fpml = message.getTrade();
        Trade trade = new Trade();
        trade.setTradeId(fpml.getTradeId());
        trade.setMessageId(message.getHeader() != null ? message.getHeader().getMessageId() : null);
        trade.setCounterparty1(fpml.getParty1().getPartyId());
        trade.setCounterparty2(fpml.getParty2().getPartyId());
        trade.setRole1(fpml.getParty1().getRole());
        trade.setRole2(fpml.getParty2().getRole());
        trade.setCurrency1(fpml.getCurrencyPair().getCurrency1());
        trade.setCurrency2(fpml.getCurrencyPair().getCurrency2());
        trade.setAmount1(fpml.getCurrencyPair().getAmount1());
        trade.setAmount2(fpml.getCurrencyPair().getAmount2());
        trade.setExchangeRate(fpml.getCurrencyPair().getExchangeRate());
        trade.setValueDate(LocalDate.parse(fpml.getValueDate()));
        trade.setTradeType(fpml.getTradeType());
        trade.setRawJson(rawJson);
        trade.setReceivedAt(Instant.now());
        return trade;
    }

    private void sleepForPollInterval() throws InterruptedException {
        TimeUnit.MILLISECONDS.sleep(queueBroker.getPollInterval().toMillis());
    }
}

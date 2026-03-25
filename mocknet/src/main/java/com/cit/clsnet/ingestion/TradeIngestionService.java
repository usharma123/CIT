package com.cit.clsnet.ingestion;

import com.cit.clsnet.config.ClsNetProperties;
import com.cit.clsnet.ingestion.util.TradeEntityMapper;
import com.cit.clsnet.ingestion.util.TradeValidator;
import com.cit.clsnet.ingestion.util.TradeXmlParser;
import com.cit.clsnet.model.QueueMessage;
import com.cit.clsnet.model.QueueName;
import com.cit.clsnet.model.Trade;
import com.cit.clsnet.model.TradeStatus;
import com.cit.clsnet.queue.QueueBroker;
import com.cit.clsnet.queue.QueueMessageTracing;
import com.cit.clsnet.repository.TradeRepository;
import com.cit.clsnet.shared.failure.FailureClassifier;
import com.cit.clsnet.shared.failure.FailureContext;
import com.cit.clsnet.shared.failure.FailureReason;
import com.cit.clsnet.shared.failure.QueueFailureDisposition;
import com.cit.clsnet.shared.failure.QueueProcessingException;
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
    private final TradeXmlParser tradeXmlParser;
    private final TradeValidator tradeValidator;
    private final TradeEntityMapper tradeEntityMapper;
    private volatile boolean running = true;

    public TradeIngestionService(
            @Qualifier("ingestionExecutor") ExecutorService executor,
            QueueBroker queueBroker,
            TradeRepository tradeRepository,
            TransactionTemplate transactionTemplate,
            CurrencyValidationService currencyValidationService,
            QueueMessageTracing queueMessageTracing,
            FailureClassifier failureClassifier,
            TradeXmlParser tradeXmlParser,
            TradeValidator tradeValidator,
            TradeEntityMapper tradeEntityMapper,
            @Lazy TradeIngestionService self,
            ClsNetProperties properties) {
        this.queueBroker = queueBroker;
        this.executor = executor;
        this.tradeRepository = tradeRepository;
        this.transactionTemplate = transactionTemplate;
        this.currencyValidationService = currencyValidationService;
        this.queueMessageTracing = queueMessageTracing;
        this.failureClassifier = failureClassifier;
        this.tradeXmlParser = tradeXmlParser;
        this.tradeValidator = tradeValidator;
        this.tradeEntityMapper = tradeEntityMapper;
        this.self = self;
        this.threadCount = properties.getThreads().getIngestion();
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
        ParsedTradeMessage parsedTrade = tradeXmlParser.parse(xml);
        ValidationResult validationResult = tradeValidator.validate(parsedTrade);

        if (!validationResult.valid()) {
            if (!validationResult.persistRejected()) {
                throw new QueueProcessingException(validationResult.message(), validationResult.reason(), false);
            }

            transactionTemplate.executeWithoutResult(status -> {
                Trade rejected = tradeEntityMapper.mapToTrade(parsedTrade);
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
            Trade trade = tradeEntityMapper.mapToTrade(parsedTrade);
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

    private void sleepForPollInterval() throws InterruptedException {
        TimeUnit.MILLISECONDS.sleep(queueBroker.getPollInterval().toMillis());
    }
}

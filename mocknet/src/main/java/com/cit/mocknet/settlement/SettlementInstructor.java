package com.cit.mocknet.settlement;

import com.cit.mocknet.config.MocknetProperties;
import com.cit.mocknet.model.NettingSet;
import com.cit.mocknet.model.QueueMessage;
import com.cit.mocknet.model.QueueName;
import com.cit.mocknet.queue.QueueBroker;
import com.cit.mocknet.queue.QueueMessageTracing;
import com.cit.mocknet.repository.NettingSetRepository;
import com.cit.mocknet.repository.SettlementInstructionRepository;
import com.cit.mocknet.settlement.util.SettlementInstructionFactory;
import com.cit.mocknet.settlement.util.SettlementMessageParser;
import com.cit.mocknet.shared.failure.FailureClassifier;
import com.cit.mocknet.shared.failure.FailureContext;
import com.cit.mocknet.shared.failure.FailureReason;
import com.cit.mocknet.shared.failure.QueueFailureDisposition;
import com.cit.mocknet.shared.failure.QueueProcessingException;
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

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Consumes the settlementQueue for any standalone settlement requests.
 * In the 2PC flow, settlement instructions are created atomically by the
 * TwoPhaseCommitCoordinator. This service handles the queue for observability
 * and can process non-2PC settlement requests.
 */
@Service
public class SettlementInstructor {

    private static final Logger log = LoggerFactory.getLogger(SettlementInstructor.class);

    private final QueueBroker queueBroker;
    private final NettingSetRepository nettingSetRepository;
    private final SettlementInstructionRepository settlementInstructionRepository;
    private final TransactionTemplate transactionTemplate;
    private final FailureClassifier failureClassifier;
    private final QueueMessageTracing queueMessageTracing;
    private final SettlementMessageParser settlementMessageParser;
    private final SettlementInstructionFactory settlementInstructionFactory;
    private final SettlementInstructor self;
    private final ExecutorService executor;
    private final int threadCount;
    private volatile boolean running = true;

    public SettlementInstructor(
            @Qualifier("settlementExecutor") ExecutorService executor,
            QueueBroker queueBroker,
            NettingSetRepository nettingSetRepository,
            SettlementInstructionRepository settlementInstructionRepository,
            TransactionTemplate transactionTemplate,
            FailureClassifier failureClassifier,
            QueueMessageTracing queueMessageTracing,
            SettlementMessageParser settlementMessageParser,
            SettlementInstructionFactory settlementInstructionFactory,
            @Lazy SettlementInstructor self,
            MocknetProperties properties) {
        this.queueBroker = queueBroker;
        this.executor = executor;
        this.nettingSetRepository = nettingSetRepository;
        this.settlementInstructionRepository = settlementInstructionRepository;
        this.transactionTemplate = transactionTemplate;
        this.failureClassifier = failureClassifier;
        this.queueMessageTracing = queueMessageTracing;
        this.settlementMessageParser = settlementMessageParser;
        this.settlementInstructionFactory = settlementInstructionFactory;
        this.self = self;
        this.threadCount = properties.getThreads().getSettlement();
    }

    @PostConstruct
    public void startConsumers() {
        for (int i = 0; i < threadCount; i++) {
            executor.submit(this::processLoop);
        }
        log.info("Settlement Instructor started with {} consumer threads (standby - primary flow via 2PC)", threadCount);
    }

    @PreDestroy
    public void stopConsumers() {
        running = false;
        executor.shutdownNow();
    }

    private void processLoop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                QueueMessage message = queueBroker.claimNext(QueueName.SETTLEMENT, Thread.currentThread().getName())
                        .orElse(null);
                if (message == null) {
                    sleepForPollInterval();
                    continue;
                }

                Span processingSpan = queueMessageTracing.startProcessingSpan(message);
                try (Scope ignored = processingSpan.makeCurrent()) {
                    try {
                        self.processSettlementMessage(message.getPayload());
                        queueBroker.complete(message);
                        queueMessageTracing.markOutcome(processingSpan, "completed");
                    } catch (QueueProcessingException e) {
                        processingSpan.recordException(e);
                        FailureContext failureContext = e.getFailureContext();
                        QueueFailureDisposition disposition = queueBroker.fail(message, failureContext);
                        queueMessageTracing.markFailure(processingSpan, failureContext, disposition);
                    } catch (Exception e) {
                        processingSpan.recordException(e);
                        FailureContext failureContext = failureClassifier.classify(e, FailureReason.PROCESSING_ERROR, "Failed to process settlement message");
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
                log.error("Error in settlement instructor", e);
            }
        }
    }

    public void processSettlementMessage(String message) {
        transactionTemplate.executeWithoutResult(status -> {
            try {
                List<Long> nettingSetIds = settlementMessageParser.parseNettingSetIds(message);
                for (Long nsId : nettingSetIds) {
                    NettingSet nettingSet = nettingSetRepository.findById(nsId)
                            .orElseThrow(() -> new QueueProcessingException("NettingSet not found: " + nsId, FailureReason.TRANSIENT_DATA_ACCESS, true));

                    settlementInstructionFactory.create(nettingSet)
                            .ifPresent(settlementInstructionRepository::save);
                }
            } catch (Exception e) {
                throw e instanceof QueueProcessingException
                        ? (QueueProcessingException) e
                        : new QueueProcessingException("Failed to process settlement message", e, FailureReason.INVALID_SETTLEMENT_MESSAGE, false);
            }
        });
    }

    private void sleepForPollInterval() throws InterruptedException {
        TimeUnit.MILLISECONDS.sleep(queueBroker.getPollInterval().toMillis());
    }
}

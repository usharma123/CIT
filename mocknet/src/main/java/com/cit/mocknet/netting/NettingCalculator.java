package com.cit.mocknet.netting;

import com.cit.mocknet.config.MocknetProperties;
import com.cit.mocknet.model.QueueMessage;
import com.cit.mocknet.model.QueueName;
import com.cit.mocknet.netting.util.NettingMessageParser;
import com.cit.mocknet.queue.QueueBroker;
import com.cit.mocknet.queue.QueueMessageTracing;
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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Consumes the nettingQueue and delegates to the 2-Phase Commit Coordinator
 * which atomically executes both netting and settlement.
 */
@Service
public class NettingCalculator {

    private static final Logger log = LoggerFactory.getLogger(NettingCalculator.class);

    private final QueueBroker queueBroker;
    private final TwoPhaseCommitCoordinator twoPhaseCommitCoordinator;
    private final NettingCutoffService nettingCutoffService;
    private final FailureClassifier failureClassifier;
    private final QueueMessageTracing queueMessageTracing;
    private final NettingCalculator self;
    private final ExecutorService executor;
    private final int threadCount;
    private final NettingMessageParser nettingMessageParser;
    private volatile boolean running = true;

    public NettingCalculator(
            @Qualifier("nettingExecutor") ExecutorService executor,
            QueueBroker queueBroker,
            TwoPhaseCommitCoordinator twoPhaseCommitCoordinator,
            NettingCutoffService nettingCutoffService,
            FailureClassifier failureClassifier,
            QueueMessageTracing queueMessageTracing,
            NettingMessageParser nettingMessageParser,
            @Lazy NettingCalculator self,
            MocknetProperties properties) {
        this.queueBroker = queueBroker;
        this.executor = executor;
        this.twoPhaseCommitCoordinator = twoPhaseCommitCoordinator;
        this.nettingCutoffService = nettingCutoffService;
        this.failureClassifier = failureClassifier;
        this.queueMessageTracing = queueMessageTracing;
        this.nettingMessageParser = nettingMessageParser;
        this.self = self;
        this.threadCount = properties.getThreads().getNetting();
    }

    @PostConstruct
    public void startConsumers() {
        for (int i = 0; i < threadCount; i++) {
            executor.submit(this::processLoop);
        }
        log.info("Netting Calculator started with {} consumer threads (2PC enabled)", threadCount);
    }

    @PreDestroy
    public void stopConsumers() {
        running = false;
        executor.shutdownNow();
    }

    private void processLoop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                QueueMessage message = queueBroker.claimNext(QueueName.NETTING, Thread.currentThread().getName())
                        .orElse(null);
                if (message == null) {
                    sleepForPollInterval();
                    continue;
                }

                Span processingSpan = queueMessageTracing.startProcessingSpan(message);
                try (Scope ignored = processingSpan.makeCurrent()) {
                    try {
                        self.processNettingMessage(message.getPayload());
                        queueBroker.complete(message);
                        queueMessageTracing.markOutcome(processingSpan, "completed");
                    } catch (QueueProcessingException e) {
                        processingSpan.recordException(e);
                        FailureContext failureContext = e.getFailureContext();
                        QueueFailureDisposition disposition = queueBroker.fail(message, failureContext);
                        queueMessageTracing.markFailure(processingSpan, failureContext, disposition);
                    } catch (Exception e) {
                        processingSpan.recordException(e);
                        FailureContext failureContext = failureClassifier.classify(e, FailureReason.PROCESSING_ERROR, "Failed to process netting message");
                        QueueFailureDisposition disposition = queueBroker.fail(message, failureContext);
                        queueMessageTracing.markFailure(processingSpan, failureContext, disposition);
                    }
                } finally {
                    queueMessageTracing.endProcessingSpan(processingSpan);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error in netting calculator", e);
            }
        }
    }

    public void processNettingMessage(String message) {
        try {
            Long matchedTradeId = nettingMessageParser.parseMatchedTradeId(message);

            log.debug("Initiating 2-Phase Commit for matchedTradeId={}", matchedTradeId);
            boolean success = twoPhaseCommitCoordinator.executeTransaction(matchedTradeId);

            if (!success) {
                throw new QueueProcessingException(
                        "2PC transaction aborted for matchedTradeId=" + matchedTradeId,
                        FailureReason.TWO_PHASE_COMMIT_ABORTED,
                        true);
            }
            log.debug("2PC transaction completed successfully for matchedTradeId={}", matchedTradeId);
        } catch (Exception e) {
            if (e instanceof QueueProcessingException queueProcessingException) {
                throw queueProcessingException;
            }
            throw new QueueProcessingException("Failed to process netting message", e, FailureReason.INVALID_NETTING_MESSAGE, false);
        }
    }

    private void sleepForPollInterval() throws InterruptedException {
        TimeUnit.MILLISECONDS.sleep(queueBroker.getPollInterval().toMillis());
    }
}

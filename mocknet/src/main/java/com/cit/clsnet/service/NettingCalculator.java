package com.cit.clsnet.service;

import com.cit.clsnet.config.ClsNetProperties;
import com.cit.clsnet.model.QueueMessage;
import com.cit.clsnet.model.QueueName;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final NettingCalculator self;
    private final ExecutorService executor;
    private final int threadCount;
    private final ObjectMapper objectMapper;
    private volatile boolean running = true;

    public NettingCalculator(
            @Qualifier("nettingExecutor") ExecutorService executor,
            QueueBroker queueBroker,
            TwoPhaseCommitCoordinator twoPhaseCommitCoordinator,
            NettingCutoffService nettingCutoffService,
            FailureClassifier failureClassifier,
            @Lazy NettingCalculator self,
            ClsNetProperties properties) {
        this.queueBroker = queueBroker;
        this.executor = executor;
        this.twoPhaseCommitCoordinator = twoPhaseCommitCoordinator;
        this.nettingCutoffService = nettingCutoffService;
        this.failureClassifier = failureClassifier;
        this.self = self;
        this.threadCount = properties.getThreads().getNetting();
        this.objectMapper = new ObjectMapper();
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

                try {
                    self.processNettingMessage(message.getPayload());
                    queueBroker.complete(message);
                } catch (QueueProcessingException e) {
                    queueBroker.fail(message, e.getFailureContext());
                } catch (Exception e) {
                    queueBroker.fail(message, failureClassifier.classify(e, FailureReason.PROCESSING_ERROR, "Failed to process netting message"));
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
            JsonNode node = objectMapper.readTree(message);
            if (!node.hasNonNull("matchedTradeId")) {
                throw new QueueProcessingException("Netting message missing matchedTradeId", FailureReason.INVALID_NETTING_MESSAGE, false);
            }
            Long matchedTradeId = node.get("matchedTradeId").asLong();

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

package com.cit.clsnet.settlement;

import com.cit.clsnet.config.ClsNetProperties;
import com.cit.clsnet.model.NettingSet;
import com.cit.clsnet.model.QueueMessage;
import com.cit.clsnet.model.QueueName;
import com.cit.clsnet.queue.QueueBroker;
import com.cit.clsnet.repository.NettingSetRepository;
import com.cit.clsnet.repository.SettlementInstructionRepository;
import com.cit.clsnet.settlement.util.SettlementInstructionFactory;
import com.cit.clsnet.settlement.util.SettlementMessageParser;
import com.cit.clsnet.shared.failure.FailureClassifier;
import com.cit.clsnet.shared.failure.FailureReason;
import com.cit.clsnet.shared.failure.QueueProcessingException;
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
            SettlementMessageParser settlementMessageParser,
            SettlementInstructionFactory settlementInstructionFactory,
            @Lazy SettlementInstructor self,
            ClsNetProperties properties) {
        this.queueBroker = queueBroker;
        this.executor = executor;
        this.nettingSetRepository = nettingSetRepository;
        this.settlementInstructionRepository = settlementInstructionRepository;
        this.transactionTemplate = transactionTemplate;
        this.failureClassifier = failureClassifier;
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

                try {
                    self.processSettlementMessage(message.getPayload());
                    queueBroker.complete(message);
                } catch (QueueProcessingException e) {
                    queueBroker.fail(message, e.getFailureContext());
                } catch (Exception e) {
                    queueBroker.fail(message, failureClassifier.classify(e, FailureReason.PROCESSING_ERROR, "Failed to process settlement message"));
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

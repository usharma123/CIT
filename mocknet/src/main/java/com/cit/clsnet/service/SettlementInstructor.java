package com.cit.clsnet.service;

import com.cit.clsnet.config.ClsNetProperties;
import com.cit.clsnet.model.NettingSet;
import com.cit.clsnet.model.QueueMessage;
import com.cit.clsnet.model.QueueName;
import com.cit.clsnet.model.SettlementInstruction;
import com.cit.clsnet.model.SettlementStatus;
import com.cit.clsnet.repository.NettingSetRepository;
import com.cit.clsnet.repository.SettlementInstructionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final SettlementInstructor self;
    private final ExecutorService executor;
    private final int threadCount;
    private final ObjectMapper objectMapper;
    private volatile boolean running = true;

    public SettlementInstructor(
            @Qualifier("settlementExecutor") ExecutorService executor,
            QueueBroker queueBroker,
            NettingSetRepository nettingSetRepository,
            SettlementInstructionRepository settlementInstructionRepository,
            TransactionTemplate transactionTemplate,
            @Lazy SettlementInstructor self,
            ClsNetProperties properties) {
        this.queueBroker = queueBroker;
        this.executor = executor;
        this.nettingSetRepository = nettingSetRepository;
        this.settlementInstructionRepository = settlementInstructionRepository;
        this.transactionTemplate = transactionTemplate;
        this.self = self;
        this.threadCount = properties.getThreads().getSettlement();
        this.objectMapper = new ObjectMapper();
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
                    queueBroker.fail(message, e.getMessage(), e.isRetryable());
                } catch (Exception e) {
                    queueBroker.fail(message, e.getMessage(), false);
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
                JsonNode node = objectMapper.readTree(message);
                JsonNode idsNode = node.get("nettingSetIds");

                for (JsonNode idNode : idsNode) {
                    Long nsId = idNode.asLong();
                    NettingSet ns = nettingSetRepository.findById(nsId)
                            .orElseThrow(() -> new QueueProcessingException("NettingSet not found: " + nsId, true));

                    BigDecimal netAmount = ns.getNetAmount();
                    if (netAmount.compareTo(BigDecimal.ZERO) == 0) {
                        continue;
                    }

                    SettlementInstruction instruction = new SettlementInstruction();
                    instruction.setNettingSetId(nsId);
                    instruction.setCurrency(ns.getCurrency());

                    if (netAmount.compareTo(BigDecimal.ZERO) > 0) {
                        instruction.setPayerParty(ns.getCounterparty2());
                        instruction.setReceiverParty(ns.getCounterparty1());
                        instruction.setAmount(netAmount);
                    } else {
                        instruction.setPayerParty(ns.getCounterparty1());
                        instruction.setReceiverParty(ns.getCounterparty2());
                        instruction.setAmount(netAmount.abs());
                    }

                    instruction.setStatus(SettlementStatus.GENERATED);
                    instruction.setGeneratedAt(Instant.now());
                    settlementInstructionRepository.save(instruction);
                }

            } catch (Exception e) {
                throw e instanceof QueueProcessingException
                        ? (QueueProcessingException) e
                        : new QueueProcessingException("Failed to process settlement message", e, false);
            }
        });
    }

    private void sleepForPollInterval() throws InterruptedException {
        TimeUnit.MILLISECONDS.sleep(queueBroker.getPollInterval().toMillis());
    }
}

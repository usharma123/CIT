package com.cit.clsnet.service;

import com.cit.clsnet.config.ClsNetProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;

/**
 * Consumes the nettingQueue and delegates to the 2-Phase Commit Coordinator
 * which atomically executes both netting and settlement.
 */
@Service
public class NettingCalculator {

    private static final Logger log = LoggerFactory.getLogger(NettingCalculator.class);

    private final BlockingQueue<String> nettingQueue;
    private final TwoPhaseCommitCoordinator twoPhaseCommitCoordinator;
    private final NettingCutoffService nettingCutoffService;
    private final ExecutorService executor;
    private final int threadCount;
    private final ObjectMapper objectMapper;
    private volatile boolean running = true;

    public NettingCalculator(
            @Qualifier("nettingQueue") BlockingQueue<String> nettingQueue,
            @Qualifier("nettingExecutor") ExecutorService executor,
            TwoPhaseCommitCoordinator twoPhaseCommitCoordinator,
            NettingCutoffService nettingCutoffService,
            ClsNetProperties properties) {
        this.nettingQueue = nettingQueue;
        this.executor = executor;
        this.twoPhaseCommitCoordinator = twoPhaseCommitCoordinator;
        this.nettingCutoffService = nettingCutoffService;
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
                String message = nettingQueue.take();
                processNettingMessage(message);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error in netting calculator", e);
            }
        }
    }

    private void processNettingMessage(String message) {
        try {
            JsonNode node = objectMapper.readTree(message);
            Long matchedTradeId = node.get("matchedTradeId").asLong();

            log.debug("Initiating 2-Phase Commit for matchedTradeId={}", matchedTradeId);
            boolean success = twoPhaseCommitCoordinator.executeTransaction(matchedTradeId);

            if (success) {
                log.debug("2PC transaction completed successfully for matchedTradeId={}", matchedTradeId);
            } else {
                log.warn("2PC transaction ABORTED for matchedTradeId={}", matchedTradeId);
            }
        } catch (Exception e) {
            log.error("Failed to process netting message", e);
        }
    }
}

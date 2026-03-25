package com.cit.clsnet.service;

import com.cit.clsnet.config.ClsNetProperties;
import com.cit.clsnet.model.MatchedTrade;
import com.cit.clsnet.model.QueueMessage;
import com.cit.clsnet.model.QueueName;
import com.cit.clsnet.model.Trade;
import com.cit.clsnet.model.TradeStatus;
import com.cit.clsnet.repository.MatchedTradeRepository;
import com.cit.clsnet.repository.TradeRepository;
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

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class TradeMatchingEngine {

    private static final Logger log = LoggerFactory.getLogger(TradeMatchingEngine.class);

    private final QueueBroker queueBroker;
    private final TradeRepository tradeRepository;
    private final MatchedTradeRepository matchedTradeRepository;
    private final TransactionTemplate transactionTemplate;
    private final TradeMatchingEngine self;
    private final ExecutorService executor;
    private final int threadCount;
    private final ObjectMapper objectMapper;
    private volatile boolean running = true;

    public TradeMatchingEngine(
            @Qualifier("matchingExecutor") ExecutorService executor,
            QueueBroker queueBroker,
            TradeRepository tradeRepository,
            MatchedTradeRepository matchedTradeRepository,
            TransactionTemplate transactionTemplate,
            @Lazy TradeMatchingEngine self,
            ClsNetProperties properties) {
        this.queueBroker = queueBroker;
        this.executor = executor;
        this.tradeRepository = tradeRepository;
        this.matchedTradeRepository = matchedTradeRepository;
        this.transactionTemplate = transactionTemplate;
        this.self = self;
        this.threadCount = properties.getThreads().getMatching();
        this.objectMapper = new ObjectMapper();
    }

    @PostConstruct
    public void startConsumers() {
        for (int i = 0; i < threadCount; i++) {
            executor.submit(this::processLoop);
        }
        log.info("Trade Matching Engine started with {} consumer threads (pessimistic locking enabled)", threadCount);
    }

    @PreDestroy
    public void stopConsumers() {
        running = false;
        executor.shutdownNow();
    }

    private void processLoop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                QueueMessage message = queueBroker.claimNext(QueueName.MATCHING, Thread.currentThread().getName())
                        .orElse(null);
                if (message == null) {
                    sleepForPollInterval();
                    continue;
                }

                try {
                    self.processMatchingMessage(message.getPayload());
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
                log.error("Error in matching engine", e);
            }
        }
    }

    public void processMatchingMessage(String message) {
        JsonNode node;
        try {
            node = objectMapper.readTree(message);
        } catch (Exception e) {
            throw new QueueProcessingException("Invalid matching message payload", e, false);
        }
        if (!node.hasNonNull("tradeId")) {
            throw new QueueProcessingException("Matching message missing tradeId", false);
        }

        String result = tryMatch(node);
        if (result == null) {
            return;
        }
        if ("RETRY".equals(result)) {
            throw new QueueProcessingException("Retry matching due to concurrency conflict", true);
        }
        queueBroker.publish(QueueName.NETTING, result);
    }

    /**
     * Attempts to match a trade. Returns:
     * - null: no match found or trade already matched (done)
     * - "RETRY": optimistic lock conflict, should retry
     * - JSON string: successful match, enqueue this for netting
     */
    private String tryMatch(JsonNode node) {
        String[] outMessage = {null};
        boolean[] shouldRetry = {false};

        try {
            transactionTemplate.executeWithoutResult(status -> {
                try {
                    Long tradeId = node.get("tradeId").asLong();

                    Trade incomingTrade = tradeRepository.findById(tradeId)
                            .orElseThrow(() -> new RuntimeException("Trade not found: " + tradeId));

                    // Skip if already matched (another thread got there first)
                    if (incomingTrade.getStatus() != TradeStatus.VALIDATED) {
                        log.debug("Trade {} already in status {}, skipping",
                                incomingTrade.getTradeId(), incomingTrade.getStatus());
                        return;
                    }

                    Optional<Trade> matchOpt = tradeRepository.findMatchCandidate(
                            incomingTrade.getCounterparty1(),
                            incomingTrade.getCounterparty2(),
                            incomingTrade.getCurrency1(),
                            incomingTrade.getCurrency2(),
                            incomingTrade.getValueDate(),
                            TradeStatus.VALIDATED,
                            incomingTrade.getId());

                    if (matchOpt.isEmpty()) {
                        log.debug("No match found yet for trade {}.", incomingTrade.getTradeId());
                        return;
                    }

                    Trade matchedWith = matchOpt.get();
                    log.debug("Match found! {} <-> {}", incomingTrade.getTradeId(), matchedWith.getTradeId());

                    Trade buyerTrade = "BUYER".equals(incomingTrade.getRole1()) ? incomingTrade : matchedWith;
                    Trade sellerTrade = "SELLER".equals(incomingTrade.getRole1()) ? incomingTrade : matchedWith;

                    MatchedTrade matched = new MatchedTrade();
                    matched.setTrade1Id(buyerTrade.getId());
                    matched.setTrade2Id(sellerTrade.getId());
                    matched.setCounterparty1(buyerTrade.getCounterparty1());
                    matched.setCounterparty2(sellerTrade.getCounterparty1());
                    matched.setCurrency1(buyerTrade.getCurrency1());
                    matched.setCurrency2(buyerTrade.getCurrency2());
                    matched.setValueDate(buyerTrade.getValueDate());
                    matched.setStatus(TradeStatus.MATCHED);
                    matched.setMatchedAt(Instant.now());
                    matched = matchedTradeRepository.save(matched);

                    // @Version on Trade will throw OptimisticLockException if stale
                    incomingTrade.setStatus(TradeStatus.MATCHED);
                    matchedWith.setStatus(TradeStatus.MATCHED);
                    tradeRepository.save(incomingTrade);
                    tradeRepository.save(matchedWith);

                    log.debug("MatchedTrade id={} created for {} and {}",
                            matched.getId(), buyerTrade.getTradeId(), sellerTrade.getTradeId());

                    outMessage[0] = String.format("{\"matchedTradeId\": %d}", matched.getId());

                } catch (Exception e) {
                    log.debug("Match attempt failed: {}", e.getMessage());
                    status.setRollbackOnly();
                    shouldRetry[0] = true;
                }
            });
        } catch (Exception e) {
            // Transaction rollback from optimistic lock — retry
            log.debug("Transaction rolled back: {}", e.getMessage());
            return "RETRY";
        }

        if (shouldRetry[0]) {
            return "RETRY";
        }
        return outMessage[0];
    }

    private void sleepForPollInterval() throws InterruptedException {
        TimeUnit.MILLISECONDS.sleep(queueBroker.getPollInterval().toMillis());
    }
}

package com.cit.clsnet.controller;

import com.cit.clsnet.model.*;
import com.cit.clsnet.repository.*;
import com.cit.clsnet.service.QueueBroker;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class StatusController {

    private final TradeRepository tradeRepository;
    private final MatchedTradeRepository matchedTradeRepository;
    private final NettingSetRepository nettingSetRepository;
    private final SettlementInstructionRepository settlementInstructionRepository;
    private final TransactionLogRepository transactionLogRepository;
    private final ParticipantVoteRepository participantVoteRepository;
    private final QueueBroker queueBroker;

    public StatusController(
            TradeRepository tradeRepository,
            MatchedTradeRepository matchedTradeRepository,
            NettingSetRepository nettingSetRepository,
            SettlementInstructionRepository settlementInstructionRepository,
            TransactionLogRepository transactionLogRepository,
            ParticipantVoteRepository participantVoteRepository,
            QueueBroker queueBroker) {
        this.tradeRepository = tradeRepository;
        this.matchedTradeRepository = matchedTradeRepository;
        this.nettingSetRepository = nettingSetRepository;
        this.settlementInstructionRepository = settlementInstructionRepository;
        this.transactionLogRepository = transactionLogRepository;
        this.participantVoteRepository = participantVoteRepository;
        this.queueBroker = queueBroker;
    }

    @GetMapping("/trades")
    public List<Trade> getAllTrades() {
        return tradeRepository.findAll();
    }

    @GetMapping("/trades/{id}")
    public Trade getTradeById(@PathVariable Long id) {
        return tradeRepository.findById(id).orElseThrow(() ->
                new RuntimeException("Trade not found: " + id));
    }

    @GetMapping("/matched-trades")
    public List<MatchedTrade> getAllMatchedTrades() {
        return matchedTradeRepository.findAll();
    }

    @GetMapping("/netting-sets")
    public List<NettingSet> getAllNettingSets() {
        return nettingSetRepository.findAll();
    }

    @GetMapping("/settlement-instructions")
    public List<SettlementInstruction> getAllSettlementInstructions() {
        return settlementInstructionRepository.findAll();
    }

    @GetMapping("/transaction-log")
    public List<TransactionLog> getTransactionLog() {
        return transactionLogRepository.findAll();
    }

    @GetMapping("/participant-votes")
    public List<ParticipantVote> getParticipantVotes() {
        return participantVoteRepository.findAll();
    }

    @GetMapping("/queues")
    public Map<String, Map<String, Long>> getQueueStatus() {
        return queueBroker.countsByQueueAndStatus();
    }

    @GetMapping("/queues/{queueName}/messages")
    public List<QueueMessage> getQueueMessages(
            @PathVariable String queueName,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit) {
        QueueName parsedQueue = QueueName.valueOf(queueName.toUpperCase());
        QueueMessageStatus parsedStatus = status == null ? null : QueueMessageStatus.valueOf(status.toUpperCase());
        return queueBroker.listMessages(parsedQueue, parsedStatus, limit);
    }

    @GetMapping("/status")
    public Map<String, Object> getPipelineStatus() {
        Map<String, Object> status = new LinkedHashMap<>();

        Map<String, Long> tradeCounts = new LinkedHashMap<>();
        for (TradeStatus ts : TradeStatus.values()) {
            long count = tradeRepository.findByStatus(ts).size();
            tradeCounts.put(ts.name(), count);
        }
        status.put("trades", tradeCounts);
        status.put("totalTrades", tradeRepository.count());
        status.put("matchedTrades", matchedTradeRepository.count());
        status.put("nettingSets", nettingSetRepository.count());
        status.put("settlementInstructions", settlementInstructionRepository.count());

        Map<String, Long> twopcCounts = new LinkedHashMap<>();
        for (TwoPhaseCommitStatus tpc : TwoPhaseCommitStatus.values()) {
            long count = transactionLogRepository.findByStatus(tpc).size();
            twopcCounts.put(tpc.name(), count);
        }
        status.put("twoPhaseCommitTransactions", twopcCounts);
        status.put("participantVotes", participantVoteRepository.count());
        status.put("queues", queueBroker.countsByQueueAndStatus());

        return status;
    }
}

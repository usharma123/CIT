package com.cit.clsnet.status;

import com.cit.clsnet.model.MatchedTrade;
import com.cit.clsnet.model.NettingSet;
import com.cit.clsnet.model.ParticipantVote;
import com.cit.clsnet.model.QueueMessage;
import com.cit.clsnet.model.QueueMessageStatus;
import com.cit.clsnet.model.QueueName;
import com.cit.clsnet.model.SettlementInstruction;
import com.cit.clsnet.model.Trade;
import com.cit.clsnet.model.TransactionLog;
import com.cit.clsnet.queue.QueueBroker;
import com.cit.clsnet.repository.MatchedTradeRepository;
import com.cit.clsnet.repository.NettingSetRepository;
import com.cit.clsnet.repository.ParticipantVoteRepository;
import com.cit.clsnet.repository.SettlementInstructionRepository;
import com.cit.clsnet.repository.TradeRepository;
import com.cit.clsnet.repository.TransactionLogRepository;
import com.cit.clsnet.status.util.StatusSnapshotAssembler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
    private final StatusSnapshotAssembler statusSnapshotAssembler;

    public StatusController(
            TradeRepository tradeRepository,
            MatchedTradeRepository matchedTradeRepository,
            NettingSetRepository nettingSetRepository,
            SettlementInstructionRepository settlementInstructionRepository,
            TransactionLogRepository transactionLogRepository,
            ParticipantVoteRepository participantVoteRepository,
            QueueBroker queueBroker,
            StatusSnapshotAssembler statusSnapshotAssembler) {
        this.tradeRepository = tradeRepository;
        this.matchedTradeRepository = matchedTradeRepository;
        this.nettingSetRepository = nettingSetRepository;
        this.settlementInstructionRepository = settlementInstructionRepository;
        this.transactionLogRepository = transactionLogRepository;
        this.participantVoteRepository = participantVoteRepository;
        this.queueBroker = queueBroker;
        this.statusSnapshotAssembler = statusSnapshotAssembler;
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
        return statusSnapshotAssembler.buildPipelineStatus();
    }
}

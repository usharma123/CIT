package com.cit.mocknet.status.util;

import com.cit.mocknet.model.TradeStatus;
import com.cit.mocknet.model.TwoPhaseCommitStatus;
import com.cit.mocknet.queue.QueueBroker;
import com.cit.mocknet.repository.MatchedTradeRepository;
import com.cit.mocknet.repository.NettingSetRepository;
import com.cit.mocknet.repository.ParticipantVoteRepository;
import com.cit.mocknet.repository.SettlementInstructionRepository;
import com.cit.mocknet.repository.TradeRepository;
import com.cit.mocknet.repository.TransactionLogRepository;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class StatusSnapshotAssembler {

    private final TradeRepository tradeRepository;
    private final MatchedTradeRepository matchedTradeRepository;
    private final NettingSetRepository nettingSetRepository;
    private final SettlementInstructionRepository settlementInstructionRepository;
    private final TransactionLogRepository transactionLogRepository;
    private final ParticipantVoteRepository participantVoteRepository;
    private final QueueBroker queueBroker;

    public StatusSnapshotAssembler(
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

    public Map<String, Object> buildPipelineStatus() {
        Map<String, Object> status = new LinkedHashMap<>();

        Map<String, Long> tradeCounts = new LinkedHashMap<>();
        for (TradeStatus tradeStatus : TradeStatus.values()) {
            tradeCounts.put(tradeStatus.name(), (long) tradeRepository.findByStatus(tradeStatus).size());
        }
        status.put("trades", tradeCounts);
        status.put("totalTrades", tradeRepository.count());
        status.put("matchedTrades", matchedTradeRepository.count());
        status.put("nettingSets", nettingSetRepository.count());
        status.put("settlementInstructions", settlementInstructionRepository.count());

        Map<String, Long> twoPhaseCommitCounts = new LinkedHashMap<>();
        for (TwoPhaseCommitStatus commitStatus : TwoPhaseCommitStatus.values()) {
            twoPhaseCommitCounts.put(commitStatus.name(), (long) transactionLogRepository.findByStatus(commitStatus).size());
        }
        status.put("twoPhaseCommitTransactions", twoPhaseCommitCounts);
        status.put("participantVotes", participantVoteRepository.count());
        status.put("queues", queueBroker.countsByQueueAndStatus());
        return status;
    }
}

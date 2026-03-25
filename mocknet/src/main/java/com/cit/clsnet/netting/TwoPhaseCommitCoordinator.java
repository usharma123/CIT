package com.cit.clsnet.netting;

import com.cit.clsnet.model.MatchedTrade;
import com.cit.clsnet.model.NettingSet;
import com.cit.clsnet.model.Trade;
import com.cit.clsnet.model.TradeStatus;
import com.cit.clsnet.model.TransactionLog;
import com.cit.clsnet.model.TwoPhaseCommitStatus;
import com.cit.clsnet.netting.util.NettingSetFactory;
import com.cit.clsnet.netting.util.ParticipantVoteFactory;
import com.cit.clsnet.repository.MatchedTradeRepository;
import com.cit.clsnet.repository.NettingSetRepository;
import com.cit.clsnet.repository.ParticipantVoteRepository;
import com.cit.clsnet.repository.SettlementInstructionRepository;
import com.cit.clsnet.repository.TradeRepository;
import com.cit.clsnet.repository.TransactionLogRepository;
import com.cit.clsnet.settlement.util.SettlementInstructionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Implements a 2-Phase Commit (2PC) protocol for the netting-to-settlement transition.
 *
 * The 2PC ensures that both the netting calculation and settlement instruction generation
 * either both succeed or both fail atomically, even though they are separate components.
 *
 * Phase 1 (PREPARE): The coordinator asks the NettingCalculator and SettlementInstructor
 *   if they can process the matched trade. Each participant validates its preconditions
 *   and votes COMMIT or ABORT.
 *
 * Phase 2 (COMMIT/ABORT): Based on the votes:
 *   - If all vote COMMIT: coordinator tells both to commit their work
 *   - If any votes ABORT: coordinator tells both to rollback
 *
 * All decisions are logged to the transaction_log table for recovery.
 */
@Service
public class TwoPhaseCommitCoordinator {

    private static final Logger log = LoggerFactory.getLogger(TwoPhaseCommitCoordinator.class);

    private final TransactionLogRepository txLogRepository;
    private final ParticipantVoteRepository voteRepository;
    private final MatchedTradeRepository matchedTradeRepository;
    private final TradeRepository tradeRepository;
    private final NettingSetRepository nettingSetRepository;
    private final SettlementInstructionRepository settlementInstructionRepository;
    private final TransactionTemplate transactionTemplate;
    private final NettingSetFactory nettingSetFactory;
    private final ParticipantVoteFactory participantVoteFactory;
    private final SettlementInstructionFactory settlementInstructionFactory;

    public TwoPhaseCommitCoordinator(
            TransactionLogRepository txLogRepository,
            ParticipantVoteRepository voteRepository,
            MatchedTradeRepository matchedTradeRepository,
            TradeRepository tradeRepository,
            NettingSetRepository nettingSetRepository,
            SettlementInstructionRepository settlementInstructionRepository,
            TransactionTemplate transactionTemplate,
            NettingSetFactory nettingSetFactory,
            ParticipantVoteFactory participantVoteFactory,
            SettlementInstructionFactory settlementInstructionFactory) {
        this.txLogRepository = txLogRepository;
        this.voteRepository = voteRepository;
        this.matchedTradeRepository = matchedTradeRepository;
        this.tradeRepository = tradeRepository;
        this.nettingSetRepository = nettingSetRepository;
        this.settlementInstructionRepository = settlementInstructionRepository;
        this.transactionTemplate = transactionTemplate;
        this.nettingSetFactory = nettingSetFactory;
        this.participantVoteFactory = participantVoteFactory;
        this.settlementInstructionFactory = settlementInstructionFactory;
    }

    public boolean executeTransaction(Long matchedTradeId) {
        String txId = "2PC-" + UUID.randomUUID().toString().substring(0, 8);

        initiate(txId, matchedTradeId);
        log.info("[2PC:{}] Transaction initiated for matchedTradeId={}", txId, matchedTradeId);

        log.info("[2PC:{}] Phase 1 - PREPARE: sending prepare requests to participants", txId);
        updateTxStatus(txId, TwoPhaseCommitStatus.PREPARE_SENT);

        boolean nettingReady = prepareNettingParticipant(txId, matchedTradeId);
        boolean settlementReady = prepareSettlementParticipant(txId, matchedTradeId);
        boolean allPrepared = nettingReady && settlementReady;

        if (allPrepared) {
            updateTxStatus(txId, TwoPhaseCommitStatus.PREPARED);
            log.info("[2PC:{}] Phase 1 complete - all participants voted COMMIT", txId);
        } else {
            log.warn("[2PC:{}] Phase 1 complete - at least one participant voted ABORT", txId);
        }

        if (allPrepared) {
            log.info("[2PC:{}] Phase 2 - COMMIT: executing commit on all participants", txId);
            updateTxStatus(txId, TwoPhaseCommitStatus.COMMITTING);

            boolean committed = commitTransaction(txId, matchedTradeId);
            if (committed) {
                updateTxStatusWithCompletion(txId, TwoPhaseCommitStatus.COMMITTED);
                log.info("[2PC:{}] Phase 2 complete - transaction COMMITTED successfully", txId);
                return true;
            }

            updateTxStatusWithCompletion(txId, TwoPhaseCommitStatus.ABORTED);
            log.error("[2PC:{}] Phase 2 - commit failed, transaction ABORTED", txId);
            return false;
        }

        log.info("[2PC:{}] Phase 2 - ABORT: rolling back all participants", txId);
        updateTxStatus(txId, TwoPhaseCommitStatus.ABORTING);
        abortTransaction(txId, matchedTradeId);
        updateTxStatusWithCompletion(txId, TwoPhaseCommitStatus.ABORTED);
        log.info("[2PC:{}] Phase 2 complete - transaction ABORTED", txId);
        return false;
    }

    private TransactionLog initiate(String txId, Long matchedTradeId) {
        return transactionTemplate.execute(status -> {
            TransactionLog txLog = new TransactionLog();
            txLog.setTransactionId(txId);
            txLog.setTransactionType("NETTING_SETTLEMENT");
            txLog.setStatus(TwoPhaseCommitStatus.INITIATED);
            txLog.setMatchedTradeId(matchedTradeId);
            txLog.setCoordinatorComponent("TwoPhaseCommitCoordinator");
            txLog.setCreatedAt(Instant.now());
            return txLogRepository.save(txLog);
        });
    }

    private boolean prepareNettingParticipant(String txId, Long matchedTradeId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            try {
                MatchedTrade matched = matchedTradeRepository.findById(matchedTradeId).orElse(null);
                if (matched == null) {
                    voteRepository.save(participantVoteFactory.abortVote(txId, "NettingCalculator", "MatchedTrade not found: " + matchedTradeId));
                    log.warn("[2PC:{}] NettingCalculator votes ABORT: matched trade not found", txId);
                    return false;
                }

                Trade trade1 = tradeRepository.findById(matched.getTrade1Id()).orElse(null);
                Trade trade2 = tradeRepository.findById(matched.getTrade2Id()).orElse(null);

                if (trade1 == null || trade2 == null) {
                    voteRepository.save(participantVoteFactory.abortVote(txId, "NettingCalculator", "Underlying trade(s) not found"));
                    log.warn("[2PC:{}] NettingCalculator votes ABORT: underlying trades missing", txId);
                    return false;
                }

                if (trade1.getStatus() != TradeStatus.MATCHED || trade2.getStatus() != TradeStatus.MATCHED) {
                    voteRepository.save(participantVoteFactory.abortVote(
                            txId,
                            "NettingCalculator",
                            "Trades not in MATCHED status: " + trade1.getStatus() + ", " + trade2.getStatus()));
                    log.warn("[2PC:{}] NettingCalculator votes ABORT: invalid trade status", txId);
                    return false;
                }

                if (trade1.getAmount1().compareTo(BigDecimal.ZERO) <= 0) {
                    voteRepository.save(participantVoteFactory.abortVote(txId, "NettingCalculator", "Invalid trade amount"));
                    log.warn("[2PC:{}] NettingCalculator votes ABORT: invalid amount", txId);
                    return false;
                }

                voteRepository.save(participantVoteFactory.commitVote(txId, "NettingCalculator", "All preconditions met for netting"));
                log.info("[2PC:{}] NettingCalculator votes COMMIT", txId);
                return true;
            } catch (Exception e) {
                voteRepository.save(participantVoteFactory.abortVote(txId, "NettingCalculator", "Exception: " + e.getMessage()));
                log.error("[2PC:{}] NettingCalculator votes ABORT due to exception", txId, e);
                return false;
            }
        }));
    }

    private boolean prepareSettlementParticipant(String txId, Long matchedTradeId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            try {
                MatchedTrade matched = matchedTradeRepository.findById(matchedTradeId).orElse(null);
                if (matched == null) {
                    voteRepository.save(participantVoteFactory.abortVote(txId, "SettlementInstructor", "MatchedTrade not found"));
                    return false;
                }

                if (matched.getCounterparty1() == null || matched.getCounterparty2() == null) {
                    voteRepository.save(participantVoteFactory.abortVote(txId, "SettlementInstructor", "Missing counterparty information"));
                    return false;
                }

                if (matched.getCurrency1() == null || matched.getCurrency2() == null) {
                    voteRepository.save(participantVoteFactory.abortVote(txId, "SettlementInstructor", "Missing currency information"));
                    return false;
                }

                voteRepository.save(participantVoteFactory.commitVote(txId, "SettlementInstructor", "All preconditions met for settlement"));
                log.info("[2PC:{}] SettlementInstructor votes COMMIT", txId);
                return true;
            } catch (Exception e) {
                voteRepository.save(participantVoteFactory.abortVote(txId, "SettlementInstructor", "Exception: " + e.getMessage()));
                log.error("[2PC:{}] SettlementInstructor votes ABORT due to exception", txId, e);
                return false;
            }
        }));
    }

    private boolean commitTransaction(String txId, Long matchedTradeId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            try {
                MatchedTrade matched = matchedTradeRepository.findById(matchedTradeId)
                        .orElseThrow(() -> new RuntimeException("MatchedTrade not found"));

                Trade buyerTrade = tradeRepository.findById(matched.getTrade1Id())
                        .orElseThrow(() -> new RuntimeException("Buyer trade not found"));
                Trade sellerTrade = tradeRepository.findById(matched.getTrade2Id())
                        .orElseThrow(() -> new RuntimeException("Seller trade not found"));

                List<NettingSet> nettingSets = nettingSetFactory.create(matchedTradeId, matched, buyerTrade).stream()
                        .map(nettingSetRepository::save)
                        .toList();

                matched.setStatus(TradeStatus.NETTED);
                matchedTradeRepository.save(matched);
                buyerTrade.setStatus(TradeStatus.NETTED);
                sellerTrade.setStatus(TradeStatus.NETTED);
                tradeRepository.save(buyerTrade);
                tradeRepository.save(sellerTrade);

                log.info("[2PC:{}] Netting committed: {} net={}, {} net={}",
                        txId, nettingSets.get(0).getCurrency(), nettingSets.get(0).getNetAmount(),
                        nettingSets.get(1).getCurrency(), nettingSets.get(1).getNetAmount());

                for (NettingSet nettingSet : nettingSets) {
                    settlementInstructionFactory.create(nettingSet)
                            .ifPresent(settlementInstructionRepository::save);
                }

                log.info("[2PC:{}] Settlement instructions committed", txId);
                return true;
            } catch (Exception e) {
                log.error("[2PC:{}] Commit failed", txId, e);
                status.setRollbackOnly();
                return false;
            }
        }));
    }

    private void abortTransaction(String txId, Long matchedTradeId) {
        transactionTemplate.executeWithoutResult(status ->
                log.info("[2PC:{}] Abort - no netting/settlement records to clean up for matchedTradeId={}", txId, matchedTradeId));
    }

    private void updateTxStatus(String txId, TwoPhaseCommitStatus newStatus) {
        transactionTemplate.executeWithoutResult(status -> {
            TransactionLog txLog = txLogRepository.findByTransactionId(txId)
                    .orElseThrow(() -> new RuntimeException("Transaction not found: " + txId));
            txLog.setStatus(newStatus);
            if (newStatus == TwoPhaseCommitStatus.PREPARED) {
                txLog.setPreparedAt(Instant.now());
            }
            if (newStatus == TwoPhaseCommitStatus.COMMITTING || newStatus == TwoPhaseCommitStatus.ABORTING) {
                txLog.setDecidedAt(Instant.now());
            }
            txLogRepository.save(txLog);
        });
    }

    private void updateTxStatusWithCompletion(String txId, TwoPhaseCommitStatus newStatus) {
        transactionTemplate.executeWithoutResult(status -> {
            TransactionLog txLog = txLogRepository.findByTransactionId(txId)
                    .orElseThrow(() -> new RuntimeException("Transaction not found: " + txId));
            txLog.setStatus(newStatus);
            txLog.setCompletedAt(Instant.now());
            txLogRepository.save(txLog);
        });
    }
}

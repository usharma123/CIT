package com.cit.clsnet.service;

import com.cit.clsnet.model.*;
import com.cit.clsnet.repository.*;
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

    public TwoPhaseCommitCoordinator(
            TransactionLogRepository txLogRepository,
            ParticipantVoteRepository voteRepository,
            MatchedTradeRepository matchedTradeRepository,
            TradeRepository tradeRepository,
            NettingSetRepository nettingSetRepository,
            SettlementInstructionRepository settlementInstructionRepository,
            TransactionTemplate transactionTemplate) {
        this.txLogRepository = txLogRepository;
        this.voteRepository = voteRepository;
        this.matchedTradeRepository = matchedTradeRepository;
        this.tradeRepository = tradeRepository;
        this.nettingSetRepository = nettingSetRepository;
        this.settlementInstructionRepository = settlementInstructionRepository;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Execute the full 2PC protocol for a matched trade:
     * netting calculation + settlement instruction generation.
     */
    public boolean executeTransaction(Long matchedTradeId) {
        String txId = "2PC-" + UUID.randomUUID().toString().substring(0, 8);

        // --- PHASE 0: INITIATE ---
        TransactionLog txLog = initiate(txId, matchedTradeId);
        log.info("[2PC:{}] Transaction initiated for matchedTradeId={}", txId, matchedTradeId);

        // --- PHASE 1: PREPARE ---
        log.info("[2PC:{}] Phase 1 - PREPARE: sending prepare requests to participants", txId);
        updateTxStatus(txId, TwoPhaseCommitStatus.PREPARE_SENT);

        boolean nettingReady = prepareNettingParticipant(txId, matchedTradeId);
        boolean settlementReady = prepareSettlementParticipant(txId, matchedTradeId);

        // Collect votes
        boolean allPrepared = nettingReady && settlementReady;

        if (allPrepared) {
            updateTxStatus(txId, TwoPhaseCommitStatus.PREPARED);
            log.info("[2PC:{}] Phase 1 complete - all participants voted COMMIT", txId);
        } else {
            log.warn("[2PC:{}] Phase 1 complete - at least one participant voted ABORT", txId);
        }

        // --- PHASE 2: COMMIT or ABORT ---
        if (allPrepared) {
            log.info("[2PC:{}] Phase 2 - COMMIT: executing commit on all participants", txId);
            updateTxStatus(txId, TwoPhaseCommitStatus.COMMITTING);

            boolean committed = commitTransaction(txId, matchedTradeId);
            if (committed) {
                updateTxStatusWithCompletion(txId, TwoPhaseCommitStatus.COMMITTED);
                log.info("[2PC:{}] Phase 2 complete - transaction COMMITTED successfully", txId);
                return true;
            } else {
                updateTxStatusWithCompletion(txId, TwoPhaseCommitStatus.ABORTED);
                log.error("[2PC:{}] Phase 2 - commit failed, transaction ABORTED", txId);
                return false;
            }
        } else {
            log.info("[2PC:{}] Phase 2 - ABORT: rolling back all participants", txId);
            updateTxStatus(txId, TwoPhaseCommitStatus.ABORTING);
            abortTransaction(txId, matchedTradeId);
            updateTxStatusWithCompletion(txId, TwoPhaseCommitStatus.ABORTED);
            log.info("[2PC:{}] Phase 2 complete - transaction ABORTED", txId);
            return false;
        }
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

    /**
     * Phase 1 - Participant: NettingCalculator
     * Validates that the matched trade exists, both underlying trades are in MATCHED status,
     * and amounts are valid for netting.
     */
    private boolean prepareNettingParticipant(String txId, Long matchedTradeId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            ParticipantVote vote = new ParticipantVote();
            vote.setTransactionId(txId);
            vote.setParticipantName("NettingCalculator");

            try {
                MatchedTrade matched = matchedTradeRepository.findById(matchedTradeId).orElse(null);
                if (matched == null) {
                    vote.setVote(VoteStatus.VOTE_ABORT);
                    vote.setReason("MatchedTrade not found: " + matchedTradeId);
                    vote.setVotedAt(Instant.now());
                    voteRepository.save(vote);
                    log.warn("[2PC:{}] NettingCalculator votes ABORT: matched trade not found", txId);
                    return false;
                }

                Trade trade1 = tradeRepository.findById(matched.getTrade1Id()).orElse(null);
                Trade trade2 = tradeRepository.findById(matched.getTrade2Id()).orElse(null);

                if (trade1 == null || trade2 == null) {
                    vote.setVote(VoteStatus.VOTE_ABORT);
                    vote.setReason("Underlying trade(s) not found");
                    vote.setVotedAt(Instant.now());
                    voteRepository.save(vote);
                    log.warn("[2PC:{}] NettingCalculator votes ABORT: underlying trades missing", txId);
                    return false;
                }

                if (trade1.getStatus() != TradeStatus.MATCHED || trade2.getStatus() != TradeStatus.MATCHED) {
                    vote.setVote(VoteStatus.VOTE_ABORT);
                    vote.setReason("Trades not in MATCHED status: " + trade1.getStatus() + ", " + trade2.getStatus());
                    vote.setVotedAt(Instant.now());
                    voteRepository.save(vote);
                    log.warn("[2PC:{}] NettingCalculator votes ABORT: invalid trade status", txId);
                    return false;
                }

                if (trade1.getAmount1().compareTo(BigDecimal.ZERO) <= 0) {
                    vote.setVote(VoteStatus.VOTE_ABORT);
                    vote.setReason("Invalid trade amount");
                    vote.setVotedAt(Instant.now());
                    voteRepository.save(vote);
                    log.warn("[2PC:{}] NettingCalculator votes ABORT: invalid amount", txId);
                    return false;
                }

                vote.setVote(VoteStatus.VOTE_COMMIT);
                vote.setReason("All preconditions met for netting");
                vote.setVotedAt(Instant.now());
                voteRepository.save(vote);
                log.info("[2PC:{}] NettingCalculator votes COMMIT", txId);
                return true;

            } catch (Exception e) {
                vote.setVote(VoteStatus.VOTE_ABORT);
                vote.setReason("Exception: " + e.getMessage());
                vote.setVotedAt(Instant.now());
                voteRepository.save(vote);
                log.error("[2PC:{}] NettingCalculator votes ABORT due to exception", txId, e);
                return false;
            }
        }));
    }

    /**
     * Phase 1 - Participant: SettlementInstructor
     * Validates that netting can produce valid settlement instructions
     * (non-zero amounts, valid counterparties).
     */
    private boolean prepareSettlementParticipant(String txId, Long matchedTradeId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            ParticipantVote vote = new ParticipantVote();
            vote.setTransactionId(txId);
            vote.setParticipantName("SettlementInstructor");

            try {
                MatchedTrade matched = matchedTradeRepository.findById(matchedTradeId).orElse(null);
                if (matched == null) {
                    vote.setVote(VoteStatus.VOTE_ABORT);
                    vote.setReason("MatchedTrade not found");
                    vote.setVotedAt(Instant.now());
                    voteRepository.save(vote);
                    return false;
                }

                if (matched.getCounterparty1() == null || matched.getCounterparty2() == null) {
                    vote.setVote(VoteStatus.VOTE_ABORT);
                    vote.setReason("Missing counterparty information");
                    vote.setVotedAt(Instant.now());
                    voteRepository.save(vote);
                    return false;
                }

                if (matched.getCurrency1() == null || matched.getCurrency2() == null) {
                    vote.setVote(VoteStatus.VOTE_ABORT);
                    vote.setReason("Missing currency information");
                    vote.setVotedAt(Instant.now());
                    voteRepository.save(vote);
                    return false;
                }

                vote.setVote(VoteStatus.VOTE_COMMIT);
                vote.setReason("All preconditions met for settlement");
                vote.setVotedAt(Instant.now());
                voteRepository.save(vote);
                log.info("[2PC:{}] SettlementInstructor votes COMMIT", txId);
                return true;

            } catch (Exception e) {
                vote.setVote(VoteStatus.VOTE_ABORT);
                vote.setReason("Exception: " + e.getMessage());
                vote.setVotedAt(Instant.now());
                voteRepository.save(vote);
                log.error("[2PC:{}] SettlementInstructor votes ABORT due to exception", txId, e);
                return false;
            }
        }));
    }

    /**
     * Phase 2 - COMMIT: Execute both netting and settlement in a single transaction.
     */
    private boolean commitTransaction(String txId, Long matchedTradeId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            try {
                MatchedTrade matched = matchedTradeRepository.findById(matchedTradeId)
                        .orElseThrow(() -> new RuntimeException("MatchedTrade not found"));

                Trade buyerTrade = tradeRepository.findById(matched.getTrade1Id())
                        .orElseThrow(() -> new RuntimeException("Buyer trade not found"));
                Trade sellerTrade = tradeRepository.findById(matched.getTrade2Id())
                        .orElseThrow(() -> new RuntimeException("Seller trade not found"));

                String buyer = matched.getCounterparty1();
                String seller = matched.getCounterparty2();

                // --- Netting ---
                NettingSet ns1 = new NettingSet();
                ns1.setCounterparty1(buyer);
                ns1.setCounterparty2(seller);
                ns1.setCurrency(matched.getCurrency1());
                ns1.setNetAmount(buyerTrade.getAmount1());
                ns1.setValueDate(matched.getValueDate());
                ns1.setMatchedTradeId(matchedTradeId);
                ns1.setCalculatedAt(Instant.now());
                ns1 = nettingSetRepository.save(ns1);

                NettingSet ns2 = new NettingSet();
                ns2.setCounterparty1(buyer);
                ns2.setCounterparty2(seller);
                ns2.setCurrency(matched.getCurrency2());
                ns2.setNetAmount(buyerTrade.getAmount2().negate());
                ns2.setValueDate(matched.getValueDate());
                ns2.setMatchedTradeId(matchedTradeId);
                ns2.setCalculatedAt(Instant.now());
                ns2 = nettingSetRepository.save(ns2);

                matched.setStatus(TradeStatus.NETTED);
                matchedTradeRepository.save(matched);
                buyerTrade.setStatus(TradeStatus.NETTED);
                sellerTrade.setStatus(TradeStatus.NETTED);
                tradeRepository.save(buyerTrade);
                tradeRepository.save(sellerTrade);

                log.info("[2PC:{}] Netting committed: {} net={}, {} net={}",
                        txId, ns1.getCurrency(), ns1.getNetAmount(),
                        ns2.getCurrency(), ns2.getNetAmount());

                // --- Settlement ---
                generateSettlementInstruction(ns1);
                generateSettlementInstruction(ns2);

                log.info("[2PC:{}] Settlement instructions committed", txId);
                return true;

            } catch (Exception e) {
                log.error("[2PC:{}] Commit failed", txId, e);
                status.setRollbackOnly();
                return false;
            }
        }));
    }

    private void generateSettlementInstruction(NettingSet ns) {
        BigDecimal netAmount = ns.getNetAmount();
        if (netAmount.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }

        SettlementInstruction instruction = new SettlementInstruction();
        instruction.setNettingSetId(ns.getId());
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

    /**
     * Phase 2 - ABORT: Revert the matched trade status back to MATCHED
     * (no netting or settlement records were created in prepare phase).
     */
    private void abortTransaction(String txId, Long matchedTradeId) {
        transactionTemplate.executeWithoutResult(status -> {
            log.info("[2PC:{}] Abort - no netting/settlement records to clean up", txId);
            // In the prepare phase we only validated, no data was modified.
            // The matched trade remains in MATCHED status for retry.
        });
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

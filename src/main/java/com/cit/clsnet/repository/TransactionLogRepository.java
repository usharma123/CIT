package com.cit.clsnet.repository;

import com.cit.clsnet.model.TransactionLog;
import com.cit.clsnet.model.TwoPhaseCommitStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TransactionLogRepository extends JpaRepository<TransactionLog, Long> {
    Optional<TransactionLog> findByTransactionId(String transactionId);
    List<TransactionLog> findByStatus(TwoPhaseCommitStatus status);
}

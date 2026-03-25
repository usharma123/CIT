package com.cit.clsnet.repository;

import com.cit.clsnet.model.QueueMessage;
import com.cit.clsnet.model.QueueMessageStatus;
import com.cit.clsnet.model.QueueName;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface QueueMessageRepository extends JpaRepository<QueueMessage, Long> {

    @Query("""
            select m.id
            from QueueMessage m
            where m.queueName = :queueName
              and m.status = :status
              and m.availableAt <= :availableAt
            order by m.createdAt asc
            """)
    List<Long> findClaimableNewIds(
            QueueName queueName,
            QueueMessageStatus status,
            Instant availableAt,
            Pageable pageable);

    @Query("""
            select m.id
            from QueueMessage m
            where m.queueName = :queueName
              and m.status = :status
              and m.claimedAt <= :claimedAt
            order by m.claimedAt asc, m.createdAt asc
            """)
    List<Long> findStaleProcessingIds(
            QueueName queueName,
            QueueMessageStatus status,
            Instant claimedAt,
            Pageable pageable);

    List<QueueMessage> findByQueueNameOrderByCreatedAtAsc(QueueName queueName, Pageable pageable);

    List<QueueMessage> findByQueueNameAndStatusOrderByCreatedAtAsc(
            QueueName queueName,
            QueueMessageStatus status,
            Pageable pageable);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update QueueMessage m
            set m.status = :processingStatus,
                m.claimedAt = :claimedAt,
                m.workerName = :workerName,
                m.completedAt = null
            where m.id = :messageId
              and m.queueName = :queueName
              and (
                    (m.status = :newStatus and m.availableAt <= :availableAt)
                 or (m.status = :processingStatus and m.claimedAt <= :staleBefore)
              )
            """)
    int claimMessage(
            @Param("messageId") Long messageId,
            @Param("queueName") QueueName queueName,
            @Param("newStatus") QueueMessageStatus newStatus,
            @Param("processingStatus") QueueMessageStatus processingStatus,
            @Param("availableAt") Instant availableAt,
            @Param("staleBefore") Instant staleBefore,
            @Param("claimedAt") Instant claimedAt,
            @Param("workerName") String workerName);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update QueueMessage m
            set m.status = :doneStatus,
                m.completedAt = :completedAt
            where m.id = :messageId
              and m.status = :processingStatus
              and m.workerName = :workerName
              and m.claimedAt = :claimedAt
            """)
    int completeClaimedMessage(
            @Param("messageId") Long messageId,
            @Param("processingStatus") QueueMessageStatus processingStatus,
            @Param("doneStatus") QueueMessageStatus doneStatus,
            @Param("workerName") String workerName,
            @Param("claimedAt") Instant claimedAt,
            @Param("completedAt") Instant completedAt);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update QueueMessage m
            set m.status = :newStatus,
                m.attempts = :attempts,
                m.availableAt = :availableAt,
                m.claimedAt = null,
                m.completedAt = null,
                m.workerName = null,
                m.lastError = :lastError
            where m.id = :messageId
              and m.status = :processingStatus
              and m.workerName = :workerName
              and m.claimedAt = :claimedAt
            """)
    int rescheduleClaimedMessage(
            @Param("messageId") Long messageId,
            @Param("processingStatus") QueueMessageStatus processingStatus,
            @Param("newStatus") QueueMessageStatus newStatus,
            @Param("workerName") String workerName,
            @Param("claimedAt") Instant claimedAt,
            @Param("attempts") int attempts,
            @Param("availableAt") Instant availableAt,
            @Param("lastError") String lastError);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update QueueMessage m
            set m.status = :failedStatus,
                m.attempts = :attempts,
                m.completedAt = :completedAt,
                m.lastError = :lastError
            where m.id = :messageId
              and m.status = :processingStatus
              and m.workerName = :workerName
              and m.claimedAt = :claimedAt
            """)
    int failClaimedMessage(
            @Param("messageId") Long messageId,
            @Param("processingStatus") QueueMessageStatus processingStatus,
            @Param("failedStatus") QueueMessageStatus failedStatus,
            @Param("workerName") String workerName,
            @Param("claimedAt") Instant claimedAt,
            @Param("attempts") int attempts,
            @Param("completedAt") Instant completedAt,
            @Param("lastError") String lastError);

    long countByQueueNameAndStatus(QueueName queueName, QueueMessageStatus status);
}

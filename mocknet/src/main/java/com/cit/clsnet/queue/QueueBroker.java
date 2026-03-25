package com.cit.clsnet.queue;

import com.cit.clsnet.config.ClsNetProperties;
import com.cit.clsnet.model.QueueMessage;
import com.cit.clsnet.model.QueueMessageStatus;
import com.cit.clsnet.model.QueueName;
import com.cit.clsnet.repository.QueueMessageRepository;
import com.cit.clsnet.shared.failure.FailureContext;
import com.cit.clsnet.shared.failure.QueueFailureDisposition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class QueueBroker {

    private static final int CLAIM_BATCH_SIZE = 10;
    private static final Logger log = LoggerFactory.getLogger(QueueBroker.class);

    private final QueueMessageRepository queueMessageRepository;
    private final ClsNetProperties properties;
    private final ObjectMapper objectMapper;

    public QueueBroker(QueueMessageRepository queueMessageRepository, ClsNetProperties properties) {
        this.queueMessageRepository = queueMessageRepository;
        this.properties = properties;
        this.objectMapper = new ObjectMapper();
    }

    @Transactional
    public QueueMessage publish(QueueName queueName, String payload) {
        QueueMessage message = new QueueMessage();
        Instant now = Instant.now();
        message.setQueueName(queueName);
        message.setPayload(payload);
        message.setStatus(QueueMessageStatus.NEW);
        message.setAttempts(0);
        message.setCreatedAt(now);
        message.setAvailableAt(now);
        return queueMessageRepository.save(message);
    }

    @Transactional
    public Optional<QueueMessage> claimNext(QueueName queueName, String workerName) {
        Instant now = Instant.now();
        Instant staleBefore = now.minus(getClaimTimeout());
        Optional<QueueMessage> fresh = claimFromCandidates(
                queueMessageRepository.findClaimableNewIds(
                        queueName, QueueMessageStatus.NEW, now, PageRequest.of(0, CLAIM_BATCH_SIZE)),
                workerName,
                now,
                staleBefore,
                queueName);
        if (fresh.isPresent()) {
            return fresh;
        }

        return claimFromCandidates(
                queueMessageRepository.findStaleProcessingIds(
                        queueName, QueueMessageStatus.PROCESSING, staleBefore, PageRequest.of(0, CLAIM_BATCH_SIZE)),
                workerName,
                now,
                staleBefore,
                queueName);
    }

    @Transactional
    public void complete(QueueMessage message) {
        int updated = queueMessageRepository.completeClaimedMessage(
                message.getId(),
                QueueMessageStatus.PROCESSING,
                QueueMessageStatus.DONE,
                message.getWorkerName(),
                message.getClaimedAt(),
                Instant.now());
        if (updated == 0) {
            log.warn("Skipping completion for queue message {} because the claim is no longer owned", message.getId());
        }
    }

    @Transactional
    public QueueFailureDisposition fail(QueueMessage message, FailureContext failureContext) {
        int nextAttempts = message.getAttempts() + 1;
        String truncatedError = truncate(failureContext.getMessage());

        if (failureContext.isRetryable() && nextAttempts < properties.getBroker().getMaxAttempts()) {
            int updated = queueMessageRepository.rescheduleClaimedMessage(
                    message.getId(),
                    QueueMessageStatus.PROCESSING,
                    QueueMessageStatus.NEW,
                    message.getWorkerName(),
                    message.getClaimedAt(),
                    nextAttempts,
                    Instant.now().plusMillis(properties.getBroker().getRetryDelayMillis()),
                    truncatedError);
            if (updated == 0) {
                log.warn("Skipping retry for queue message {} because the claim is no longer owned", message.getId());
            }
            return QueueFailureDisposition.RETRIED;
        }

        Instant failedAt = Instant.now();
        int updated = queueMessageRepository.failClaimedMessage(
                message.getId(),
                QueueMessageStatus.PROCESSING,
                QueueMessageStatus.FAILED,
                message.getWorkerName(),
                message.getClaimedAt(),
                nextAttempts,
                failedAt,
                truncatedError);
        if (updated == 0) {
            log.warn("Skipping failure transition for queue message {} because the claim is no longer owned", message.getId());
            return QueueFailureDisposition.FAILED;
        }
        publishDeadLetter(message, failureContext, nextAttempts, failedAt);
        return QueueFailureDisposition.FAILED;
    }

    @Transactional(readOnly = true)
    public Map<String, Map<String, Long>> countsByQueueAndStatus() {
        Map<String, Map<String, Long>> result = new LinkedHashMap<>();
        for (QueueName queueName : QueueName.values()) {
            Map<String, Long> statuses = new LinkedHashMap<>();
            for (QueueMessageStatus status : QueueMessageStatus.values()) {
                statuses.put(status.name(), queueMessageRepository.countByQueueNameAndStatus(queueName, status));
            }
            result.put(queueName.name(), statuses);
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<QueueMessage> listMessages(QueueName queueName, QueueMessageStatus status, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        if (status == null) {
            return queueMessageRepository.findByQueueNameOrderByCreatedAtAsc(queueName, PageRequest.of(0, safeLimit));
        }
        return queueMessageRepository.findByQueueNameAndStatusOrderByCreatedAtAsc(
                queueName, status, PageRequest.of(0, safeLimit));
    }

    public Duration getPollInterval() {
        return Duration.ofMillis(properties.getBroker().getPollIntervalMillis());
    }

    public Duration getClaimTimeout() {
        return Duration.ofSeconds(properties.getBroker().getClaimTimeoutSeconds());
    }

    private Optional<QueueMessage> claimFromCandidates(
            List<Long> candidateIds,
            String workerName,
            Instant now,
            Instant staleBefore,
            QueueName queueName) {
        for (Long candidateId : candidateIds) {
            int updated = queueMessageRepository.claimMessage(
                    candidateId,
                    queueName,
                    QueueMessageStatus.NEW,
                    QueueMessageStatus.PROCESSING,
                    now,
                    staleBefore,
                    now,
                    workerName);
            if (updated == 1) {
                return queueMessageRepository.findById(candidateId);
            }
        }
        return Optional.empty();
    }

    private String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 4000 ? error : error.substring(0, 4000);
    }

    private void publishDeadLetter(QueueMessage message, FailureContext failureContext, int attempts, Instant failedAt) {
        if (message.getQueueName() == QueueName.DEAD_LETTER) {
            return;
        }

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("originalQueue", message.getQueueName().name());
            payload.put("originalPayload", message.getPayload());
            payload.put("attempts", attempts);
            payload.put("workerName", message.getWorkerName());
            payload.put("reasonCode", failureContext.getReasonCode());
            payload.put("errorMessage", truncate(failureContext.getMessage()));
            payload.put("failedAt", failedAt.toString());
            payload.put("retryable", failureContext.isRetryable());
            publish(QueueName.DEAD_LETTER, objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.error("Failed to publish DLQ message for queue message {}", message.getId(), e);
        }
    }
}

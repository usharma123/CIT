package com.cit.mocknet.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "queue_messages", indexes = {
        @Index(name = "idx_queue_operation", columnList = "operationId"),
        @Index(name = "idx_queue_business", columnList = "businessId"),
        @Index(name = "idx_queue_messages_queue_status_available", columnList = "queueName,status,availableAt"),
        @Index(name = "idx_queue_messages_queue_status_claimed", columnList = "queueName,status,claimedAt")
})
public class QueueMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private QueueName queueName;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private QueueMessageStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant availableAt;

    private Instant claimedAt;

    private Instant completedAt;

    @Column(length = 255)
    private String workerName;

    @Column(columnDefinition = "text")
    private String lastError;

    @Column(length = 512)
    private String traceContext;

    @Column(length = 512)
    private String traceState;

    public String getTraceState() { return traceState; }

    public void setTraceState(String traceState) { this.traceState = traceState; }

    @Column(length = 36)
    private String operationId;
    @Column(length = 256)
    private String businessId;
    @Column(length = 32)
    private String outcome;
    public String getOperationId() { return operationId; }
    public void setOperationId(String value) { operationId = value; }
    public String getBusinessId() { return businessId; }
    public void setBusinessId(String value) { businessId = value; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String value) { outcome = value; }

    @Version
    private Long version;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public QueueName getQueueName() {
        return queueName;
    }

    public void setQueueName(QueueName queueName) {
        this.queueName = queueName;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public QueueMessageStatus getStatus() {
        return status;
    }

    public void setStatus(QueueMessageStatus status) {
        this.status = status;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getAvailableAt() {
        return availableAt;
    }

    public void setAvailableAt(Instant availableAt) {
        this.availableAt = availableAt;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public void setClaimedAt(Instant claimedAt) {
        this.claimedAt = claimedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public String getWorkerName() {
        return workerName;
    }

    public void setWorkerName(String workerName) {
        this.workerName = workerName;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public String getTraceContext() {
        return traceContext;
    }

    public void setTraceContext(String traceContext) {
        this.traceContext = traceContext;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}

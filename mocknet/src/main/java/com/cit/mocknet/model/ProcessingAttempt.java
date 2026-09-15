package com.cit.mocknet.model;

import jakarta.persistence.*;
import java.time.Instant;

/** Persisted with the queue claim and its disposition; independent of trace sampling. */
@Entity
@Table(name = "processing_attempts", indexes = {
    @Index(name = "idx_attempt_queue_claim", columnList = "queueMessageId,claimedAt", unique = true),
    @Index(name = "idx_attempt_operation", columnList = "operationId")
})
public class ProcessingAttempt {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long queueMessageId;
    @Column(length = 36) public String operationId;
    @Column(length = 32) public String queueName;
    public Instant claimedAt;
    public Instant finishedAt;
    public int attemptNumber;
    public double waitSeconds;
    public double processingSeconds;
    @Column(length = 32) public String outcome;
    @Column(length = 128) public String reason;
    @Column(length = 255) public String worker;
    @Column(length = 128) public String instance;
    @Column(length = 128) public String serviceVersion;
    @Column(length = 32) public String traceId;
    @Column(length = 16) public String spanId;
}

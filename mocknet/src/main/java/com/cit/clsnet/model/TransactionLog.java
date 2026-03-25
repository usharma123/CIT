package com.cit.clsnet.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "transaction_log")
public class TransactionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String transactionId;

    @Column(nullable = false)
    private String transactionType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TwoPhaseCommitStatus status;

    private Long matchedTradeId;

    @Column(nullable = false)
    private String coordinatorComponent;

    private Instant createdAt;
    private Instant preparedAt;
    private Instant decidedAt;
    private Instant completedAt;

    public TransactionLog() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }

    public String getTransactionType() { return transactionType; }
    public void setTransactionType(String transactionType) { this.transactionType = transactionType; }

    public TwoPhaseCommitStatus getStatus() { return status; }
    public void setStatus(TwoPhaseCommitStatus status) { this.status = status; }

    public Long getMatchedTradeId() { return matchedTradeId; }
    public void setMatchedTradeId(Long matchedTradeId) { this.matchedTradeId = matchedTradeId; }

    public String getCoordinatorComponent() { return coordinatorComponent; }
    public void setCoordinatorComponent(String coordinatorComponent) { this.coordinatorComponent = coordinatorComponent; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getPreparedAt() { return preparedAt; }
    public void setPreparedAt(Instant preparedAt) { this.preparedAt = preparedAt; }

    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}

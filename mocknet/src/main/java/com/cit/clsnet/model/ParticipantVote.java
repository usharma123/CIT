package com.cit.clsnet.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "participant_votes")
public class ParticipantVote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String transactionId;

    @Column(nullable = false)
    private String participantName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VoteStatus vote;

    private String reason;

    private Instant votedAt;

    public ParticipantVote() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }

    public String getParticipantName() { return participantName; }
    public void setParticipantName(String participantName) { this.participantName = participantName; }

    public VoteStatus getVote() { return vote; }
    public void setVote(VoteStatus vote) { this.vote = vote; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public Instant getVotedAt() { return votedAt; }
    public void setVotedAt(Instant votedAt) { this.votedAt = votedAt; }
}

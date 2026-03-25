package com.cit.clsnet.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "settlement_instructions")
public class SettlementInstruction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long nettingSetId;

    @Column(nullable = false)
    private String payerParty;

    @Column(nullable = false)
    private String receiverParty;

    @Column(nullable = false)
    private String currency;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SettlementStatus status;

    private Instant generatedAt;

    public SettlementInstruction() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getNettingSetId() { return nettingSetId; }
    public void setNettingSetId(Long nettingSetId) { this.nettingSetId = nettingSetId; }

    public String getPayerParty() { return payerParty; }
    public void setPayerParty(String payerParty) { this.payerParty = payerParty; }

    public String getReceiverParty() { return receiverParty; }
    public void setReceiverParty(String receiverParty) { this.receiverParty = receiverParty; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public SettlementStatus getStatus() { return status; }
    public void setStatus(SettlementStatus status) { this.status = status; }

    public Instant getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(Instant generatedAt) { this.generatedAt = generatedAt; }
}

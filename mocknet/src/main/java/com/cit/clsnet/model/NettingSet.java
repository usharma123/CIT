package com.cit.clsnet.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "netting_sets")
public class NettingSet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String counterparty1;

    @Column(nullable = false)
    private String counterparty2;

    @Column(nullable = false)
    private String currency;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal netAmount;

    @Column(nullable = false)
    private LocalDate valueDate;

    private Long matchedTradeId;

    private Instant calculatedAt;

    public NettingSet() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCounterparty1() { return counterparty1; }
    public void setCounterparty1(String counterparty1) { this.counterparty1 = counterparty1; }

    public String getCounterparty2() { return counterparty2; }
    public void setCounterparty2(String counterparty2) { this.counterparty2 = counterparty2; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public BigDecimal getNetAmount() { return netAmount; }
    public void setNetAmount(BigDecimal netAmount) { this.netAmount = netAmount; }

    public LocalDate getValueDate() { return valueDate; }
    public void setValueDate(LocalDate valueDate) { this.valueDate = valueDate; }

    public Long getMatchedTradeId() { return matchedTradeId; }
    public void setMatchedTradeId(Long matchedTradeId) { this.matchedTradeId = matchedTradeId; }

    public Instant getCalculatedAt() { return calculatedAt; }
    public void setCalculatedAt(Instant calculatedAt) { this.calculatedAt = calculatedAt; }
}

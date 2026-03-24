package com.cit.clsnet.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "matched_trades")
public class MatchedTrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long trade1Id;

    @Column(nullable = false)
    private Long trade2Id;

    private String counterparty1;
    private String counterparty2;
    private String currency1;
    private String currency2;
    private LocalDate valueDate;

    @Enumerated(EnumType.STRING)
    private TradeStatus status;

    private Instant matchedAt;

    public MatchedTrade() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTrade1Id() { return trade1Id; }
    public void setTrade1Id(Long trade1Id) { this.trade1Id = trade1Id; }

    public Long getTrade2Id() { return trade2Id; }
    public void setTrade2Id(Long trade2Id) { this.trade2Id = trade2Id; }

    public String getCounterparty1() { return counterparty1; }
    public void setCounterparty1(String counterparty1) { this.counterparty1 = counterparty1; }

    public String getCounterparty2() { return counterparty2; }
    public void setCounterparty2(String counterparty2) { this.counterparty2 = counterparty2; }

    public String getCurrency1() { return currency1; }
    public void setCurrency1(String currency1) { this.currency1 = currency1; }

    public String getCurrency2() { return currency2; }
    public void setCurrency2(String currency2) { this.currency2 = currency2; }

    public LocalDate getValueDate() { return valueDate; }
    public void setValueDate(LocalDate valueDate) { this.valueDate = valueDate; }

    public TradeStatus getStatus() { return status; }
    public void setStatus(TradeStatus status) { this.status = status; }

    public Instant getMatchedAt() { return matchedAt; }
    public void setMatchedAt(Instant matchedAt) { this.matchedAt = matchedAt; }
}

package com.cit.clsnet.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "trades")
public class Trade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String tradeId;

    private String messageId;

    @Column(nullable = false)
    private String counterparty1;

    @Column(nullable = false)
    private String counterparty2;

    private String role1;
    private String role2;

    @Column(nullable = false)
    private String currency1;

    @Column(nullable = false)
    private String currency2;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount1;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount2;

    @Column(precision = 19, scale = 7)
    private BigDecimal exchangeRate;

    @Column(nullable = false)
    private LocalDate valueDate;

    private String tradeType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TradeStatus status;

    @Column(columnDefinition = "CLOB")
    private String rawJson;

    private Instant receivedAt;

    @Version
    private Long version;

    public Trade() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTradeId() { return tradeId; }
    public void setTradeId(String tradeId) { this.tradeId = tradeId; }

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    public String getCounterparty1() { return counterparty1; }
    public void setCounterparty1(String counterparty1) { this.counterparty1 = counterparty1; }

    public String getCounterparty2() { return counterparty2; }
    public void setCounterparty2(String counterparty2) { this.counterparty2 = counterparty2; }

    public String getRole1() { return role1; }
    public void setRole1(String role1) { this.role1 = role1; }

    public String getRole2() { return role2; }
    public void setRole2(String role2) { this.role2 = role2; }

    public String getCurrency1() { return currency1; }
    public void setCurrency1(String currency1) { this.currency1 = currency1; }

    public String getCurrency2() { return currency2; }
    public void setCurrency2(String currency2) { this.currency2 = currency2; }

    public BigDecimal getAmount1() { return amount1; }
    public void setAmount1(BigDecimal amount1) { this.amount1 = amount1; }

    public BigDecimal getAmount2() { return amount2; }
    public void setAmount2(BigDecimal amount2) { this.amount2 = amount2; }

    public BigDecimal getExchangeRate() { return exchangeRate; }
    public void setExchangeRate(BigDecimal exchangeRate) { this.exchangeRate = exchangeRate; }

    public LocalDate getValueDate() { return valueDate; }
    public void setValueDate(LocalDate valueDate) { this.valueDate = valueDate; }

    public String getTradeType() { return tradeType; }
    public void setTradeType(String tradeType) { this.tradeType = tradeType; }

    public TradeStatus getStatus() { return status; }
    public void setStatus(TradeStatus status) { this.status = status; }

    public String getRawJson() { return rawJson; }
    public void setRawJson(String rawJson) { this.rawJson = rawJson; }

    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}

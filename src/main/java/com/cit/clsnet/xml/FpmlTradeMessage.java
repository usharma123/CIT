package com.cit.clsnet.xml;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

import java.math.BigDecimal;

@JacksonXmlRootElement(localName = "tradeMessage")
public class FpmlTradeMessage {

    @JacksonXmlProperty(localName = "header")
    private Header header;

    @JacksonXmlProperty(localName = "trade")
    private FpmlTrade trade;

    public Header getHeader() { return header; }
    public void setHeader(Header header) { this.header = header; }

    public FpmlTrade getTrade() { return trade; }
    public void setTrade(FpmlTrade trade) { this.trade = trade; }

    public static class Header {
        @JacksonXmlProperty(localName = "messageId")
        private String messageId;

        @JacksonXmlProperty(localName = "creationTimestamp")
        private String creationTimestamp;

        public String getMessageId() { return messageId; }
        public void setMessageId(String messageId) { this.messageId = messageId; }

        public String getCreationTimestamp() { return creationTimestamp; }
        public void setCreationTimestamp(String creationTimestamp) { this.creationTimestamp = creationTimestamp; }
    }

    public static class FpmlTrade {
        @JacksonXmlProperty(localName = "tradeId")
        private String tradeId;

        @JacksonXmlProperty(localName = "tradeType")
        private String tradeType;

        @JacksonXmlProperty(localName = "party1")
        private Party party1;

        @JacksonXmlProperty(localName = "party2")
        private Party party2;

        @JacksonXmlProperty(localName = "currencyPair")
        private CurrencyPair currencyPair;

        @JacksonXmlProperty(localName = "valueDate")
        private String valueDate;

        public String getTradeId() { return tradeId; }
        public void setTradeId(String tradeId) { this.tradeId = tradeId; }

        public String getTradeType() { return tradeType; }
        public void setTradeType(String tradeType) { this.tradeType = tradeType; }

        public Party getParty1() { return party1; }
        public void setParty1(Party party1) { this.party1 = party1; }

        public Party getParty2() { return party2; }
        public void setParty2(Party party2) { this.party2 = party2; }

        public CurrencyPair getCurrencyPair() { return currencyPair; }
        public void setCurrencyPair(CurrencyPair currencyPair) { this.currencyPair = currencyPair; }

        public String getValueDate() { return valueDate; }
        public void setValueDate(String valueDate) { this.valueDate = valueDate; }
    }

    public static class Party {
        @JacksonXmlProperty(localName = "partyId")
        private String partyId;

        @JacksonXmlProperty(localName = "role")
        private String role;

        public String getPartyId() { return partyId; }
        public void setPartyId(String partyId) { this.partyId = partyId; }

        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
    }

    public static class CurrencyPair {
        @JacksonXmlProperty(localName = "currency1")
        private String currency1;

        @JacksonXmlProperty(localName = "amount1")
        private BigDecimal amount1;

        @JacksonXmlProperty(localName = "currency2")
        private String currency2;

        @JacksonXmlProperty(localName = "amount2")
        private BigDecimal amount2;

        @JacksonXmlProperty(localName = "exchangeRate")
        private BigDecimal exchangeRate;

        public String getCurrency1() { return currency1; }
        public void setCurrency1(String currency1) { this.currency1 = currency1; }

        public BigDecimal getAmount1() { return amount1; }
        public void setAmount1(BigDecimal amount1) { this.amount1 = amount1; }

        public String getCurrency2() { return currency2; }
        public void setCurrency2(String currency2) { this.currency2 = currency2; }

        public BigDecimal getAmount2() { return amount2; }
        public void setAmount2(BigDecimal amount2) { this.amount2 = amount2; }

        public BigDecimal getExchangeRate() { return exchangeRate; }
        public void setExchangeRate(BigDecimal exchangeRate) { this.exchangeRate = exchangeRate; }
    }
}

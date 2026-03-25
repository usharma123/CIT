package com.cit.clsnet.ingestion.util;

import com.cit.clsnet.ingestion.ParsedTradeMessage;
import com.cit.clsnet.shared.failure.FailureReason;
import com.cit.clsnet.shared.failure.QueueProcessingException;
import com.cit.clsnet.xml.FpmlTradeMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

@Component
public class TradeXmlParser {

    private final XmlMapper xmlMapper;
    private final ObjectMapper jsonMapper;

    public TradeXmlParser() {
        this.xmlMapper = new XmlMapper();
        this.jsonMapper = new ObjectMapper();
    }

    public ParsedTradeMessage parse(String xml) {
        try {
            FpmlTradeMessage message = xmlMapper.readValue(xml, FpmlTradeMessage.class);
            String json = jsonMapper.writeValueAsString(message);
            FpmlTradeMessage.FpmlTrade trade = message.getTrade();
            String valueDateText = trade == null ? null : normalizeString(trade.getValueDate());
            LocalDate valueDate = null;
            if (valueDateText != null) {
                try {
                    valueDate = LocalDate.parse(valueDateText);
                } catch (DateTimeParseException e) {
                    throw new QueueProcessingException("Trade valueDate is invalid", e, FailureReason.INVALID_VALUE_DATE, false);
                }
            }

            return new ParsedTradeMessage(
                    json,
                    normalizeString(message.getHeader() == null ? null : message.getHeader().getMessageId()),
                    normalizeString(trade == null ? null : trade.getTradeId()),
                    normalizeString(trade == null ? null : trade.getTradeType()),
                    normalizeString(trade == null || trade.getParty1() == null ? null : trade.getParty1().getPartyId()),
                    normalizeString(trade == null || trade.getParty2() == null ? null : trade.getParty2().getPartyId()),
                    normalizeString(trade == null || trade.getParty1() == null ? null : trade.getParty1().getRole()),
                    normalizeString(trade == null || trade.getParty2() == null ? null : trade.getParty2().getRole()),
                    normalizeCurrency(trade == null || trade.getCurrencyPair() == null ? null : trade.getCurrencyPair().getCurrency1()),
                    trade == null || trade.getCurrencyPair() == null ? null : trade.getCurrencyPair().getAmount1(),
                    normalizeCurrency(trade == null || trade.getCurrencyPair() == null ? null : trade.getCurrencyPair().getCurrency2()),
                    trade == null || trade.getCurrencyPair() == null ? null : trade.getCurrencyPair().getAmount2(),
                    trade == null || trade.getCurrencyPair() == null ? null : trade.getCurrencyPair().getExchangeRate(),
                    valueDate);
        } catch (QueueProcessingException e) {
            throw e;
        } catch (Exception e) {
            throw new QueueProcessingException("Failed to parse trade XML", e, FailureReason.INVALID_XML, false);
        }
    }

    private String normalizeString(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String normalizeCurrency(String value) {
        String normalized = normalizeString(value);
        return normalized == null ? null : normalized.toUpperCase();
    }
}

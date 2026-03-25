package com.cit.clsnet.ingestion.util;

import com.cit.clsnet.config.ClsNetProperties;
import com.cit.clsnet.ingestion.CurrencyValidationService;
import com.cit.clsnet.ingestion.ParsedTradeMessage;
import com.cit.clsnet.ingestion.ValidationResult;
import com.cit.clsnet.model.Trade;
import com.cit.clsnet.shared.failure.FailureReason;
import com.cit.clsnet.shared.failure.QueueProcessingException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngestionUtilitiesTest {

    private static final String VALID_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tradeMessage>
              <header>
                <messageId>MSG-UTIL-001</messageId>
              </header>
              <trade>
                <tradeId>TRD-UTIL-001</tradeId>
                <tradeType>SPOT</tradeType>
                <party1>
                  <partyId>BANK_A</partyId>
                  <role>BUYER</role>
                </party1>
                <party2>
                  <partyId>BANK_B</partyId>
                  <role>SELLER</role>
                </party2>
                <currencyPair>
                  <currency1>usd</currency1>
                  <amount1>100.00</amount1>
                  <currency2>eur</currency2>
                  <amount2>92.00</amount2>
                  <exchangeRate>0.92</exchangeRate>
                </currencyPair>
                <valueDate>2026-04-01</valueDate>
              </trade>
            </tradeMessage>
            """;

    @Test
    void tradeXmlParser_parsesNormalizedFields() {
        TradeXmlParser parser = new TradeXmlParser();

        ParsedTradeMessage parsed = parser.parse(VALID_XML);

        assertEquals("MSG-UTIL-001", parsed.messageId());
        assertEquals("TRD-UTIL-001", parsed.tradeId());
        assertEquals("USD", parsed.currency1());
        assertEquals("EUR", parsed.currency2());
        assertEquals(LocalDate.of(2026, 4, 1), parsed.valueDate());
    }

    @Test
    void tradeXmlParser_rejectsMalformedXml() {
        TradeXmlParser parser = new TradeXmlParser();

        QueueProcessingException exception = assertThrows(QueueProcessingException.class, () -> parser.parse("<tradeMessage>"));

        assertEquals(FailureReason.INVALID_XML, exception.getFailureContext().getReason());
    }

    @Test
    void tradeValidator_returnsSoftRejectForUnsupportedCurrency() {
        TradeValidator validator = new TradeValidator(new CurrencyValidationService(new ClsNetProperties()));

        ValidationResult result = validator.validate(new ParsedTradeMessage(
                "{}",
                "MSG-UTIL-002",
                "TRD-UTIL-002",
                "SPOT",
                "BANK_A",
                "BANK_B",
                "BUYER",
                "SELLER",
                "USD",
                new BigDecimal("100.00"),
                "ZZZ",
                new BigDecimal("90.00"),
                new BigDecimal("0.90"),
                LocalDate.of(2026, 4, 1)));

        assertTrue(!result.valid() && result.persistRejected());
        assertEquals(FailureReason.UNSUPPORTED_CURRENCY, result.reason());
    }

    @Test
    void tradeEntityMapper_mapsParsedTradeToEntity() {
        TradeEntityMapper mapper = new TradeEntityMapper();

        Trade trade = mapper.mapToTrade(new ParsedTradeMessage(
                "{\"tradeId\":\"TRD-UTIL-003\"}",
                "MSG-UTIL-003",
                "TRD-UTIL-003",
                "FORWARD",
                "BANK_A",
                "BANK_B",
                "BUYER",
                "SELLER",
                "USD",
                new BigDecimal("250.00"),
                "JPY",
                new BigDecimal("38000.00"),
                new BigDecimal("152.00"),
                LocalDate.of(2026, 4, 2)));

        assertEquals("TRD-UTIL-003", trade.getTradeId());
        assertEquals("FORWARD", trade.getTradeType());
        assertEquals("USD", trade.getCurrency1());
        assertEquals("JPY", trade.getCurrency2());
    }
}

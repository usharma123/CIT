package com.cit.clsnet.matching.util;

import com.cit.clsnet.model.MatchedTrade;
import com.cit.clsnet.model.Trade;
import com.cit.clsnet.shared.failure.QueueProcessingException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MatchingUtilitiesTest {

    @Test
    void matchingMessageParser_readsTradeId() {
        MatchingMessageParser parser = new MatchingMessageParser();

        long tradeId = parser.parseTradeId("{\"tradeId\":42}");

        assertEquals(42L, tradeId);
    }

    @Test
    void matchingMessageParser_rejectsMissingTradeId() {
        MatchingMessageParser parser = new MatchingMessageParser();

        assertThrows(QueueProcessingException.class, () -> parser.parseTradeId("{\"other\":42}"));
    }

    @Test
    void matchedTradeFactory_buildsBuyerSellerView() {
        MatchedTradeFactory factory = new MatchedTradeFactory();
        Trade buyer = trade(10L, "TRD-BUY", "BANK_A", "BUYER", "USD", "EUR");
        Trade seller = trade(20L, "TRD-SELL", "BANK_B", "SELLER", "USD", "EUR");

        MatchedTrade matchedTrade = factory.create(buyer, seller);

        assertEquals(10L, matchedTrade.getTrade1Id());
        assertEquals(20L, matchedTrade.getTrade2Id());
        assertEquals("BANK_A", matchedTrade.getCounterparty1());
        assertEquals("BANK_B", matchedTrade.getCounterparty2());
        assertEquals(LocalDate.of(2026, 4, 1), matchedTrade.getValueDate());
    }

    private Trade trade(Long id, String tradeId, String counterparty, String role1, String currency1, String currency2) {
        Trade trade = new Trade();
        trade.setId(id);
        trade.setTradeId(tradeId);
        trade.setCounterparty1(counterparty);
        trade.setRole1(role1);
        trade.setCurrency1(currency1);
        trade.setCurrency2(currency2);
        trade.setValueDate(LocalDate.of(2026, 4, 1));
        return trade;
    }
}

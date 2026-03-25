package com.cit.clsnet.netting.util;

import com.cit.clsnet.model.MatchedTrade;
import com.cit.clsnet.model.NettingSet;
import com.cit.clsnet.model.ParticipantVote;
import com.cit.clsnet.model.Trade;
import com.cit.clsnet.model.VoteStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NettingUtilitiesTest {

    @Test
    void nettingMessageParser_readsMatchedTradeId() {
        NettingMessageParser parser = new NettingMessageParser();

        long matchedTradeId = parser.parseMatchedTradeId("{\"matchedTradeId\":77}");

        assertEquals(77L, matchedTradeId);
    }

    @Test
    void nettingSetFactory_createsTwoCurrencyLegs() {
        NettingSetFactory factory = new NettingSetFactory();
        MatchedTrade matchedTrade = new MatchedTrade();
        matchedTrade.setCounterparty1("BANK_A");
        matchedTrade.setCounterparty2("BANK_B");
        matchedTrade.setCurrency1("USD");
        matchedTrade.setCurrency2("EUR");
        matchedTrade.setValueDate(LocalDate.of(2026, 4, 1));

        Trade buyerTrade = new Trade();
        buyerTrade.setAmount1(new BigDecimal("500000.00"));
        buyerTrade.setAmount2(new BigDecimal("395000.00"));

        List<NettingSet> nettingSets = factory.create(12L, matchedTrade, buyerTrade);

        assertEquals(2, nettingSets.size());
        assertEquals("USD", nettingSets.get(0).getCurrency());
        assertEquals(new BigDecimal("500000.00"), nettingSets.get(0).getNetAmount());
        assertEquals("EUR", nettingSets.get(1).getCurrency());
        assertEquals(new BigDecimal("-395000.00"), nettingSets.get(1).getNetAmount());
    }

    @Test
    void participantVoteFactory_createsCommitVote() {
        ParticipantVoteFactory factory = new ParticipantVoteFactory();

        ParticipantVote vote = factory.commitVote("2PC-1234", "NettingCalculator", "ready");

        assertEquals("2PC-1234", vote.getTransactionId());
        assertEquals("NettingCalculator", vote.getParticipantName());
        assertEquals(VoteStatus.VOTE_COMMIT, vote.getVote());
        assertEquals("ready", vote.getReason());
    }
}

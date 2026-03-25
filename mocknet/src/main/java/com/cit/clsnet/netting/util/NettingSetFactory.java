package com.cit.clsnet.netting.util;

import com.cit.clsnet.model.MatchedTrade;
import com.cit.clsnet.model.NettingSet;
import com.cit.clsnet.model.Trade;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class NettingSetFactory {

    public List<NettingSet> create(long matchedTradeId, MatchedTrade matchedTrade, Trade buyerTrade) {
        String buyer = matchedTrade.getCounterparty1();
        String seller = matchedTrade.getCounterparty2();

        NettingSet first = new NettingSet();
        first.setCounterparty1(buyer);
        first.setCounterparty2(seller);
        first.setCurrency(matchedTrade.getCurrency1());
        first.setNetAmount(buyerTrade.getAmount1());
        first.setValueDate(matchedTrade.getValueDate());
        first.setMatchedTradeId(matchedTradeId);
        first.setCalculatedAt(Instant.now());

        NettingSet second = new NettingSet();
        second.setCounterparty1(buyer);
        second.setCounterparty2(seller);
        second.setCurrency(matchedTrade.getCurrency2());
        second.setNetAmount(buyerTrade.getAmount2().negate());
        second.setValueDate(matchedTrade.getValueDate());
        second.setMatchedTradeId(matchedTradeId);
        second.setCalculatedAt(Instant.now());

        return List.of(first, second);
    }
}

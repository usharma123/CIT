package com.cit.clsnet.matching.util;

import com.cit.clsnet.model.MatchedTrade;
import com.cit.clsnet.model.Trade;
import com.cit.clsnet.model.TradeStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class MatchedTradeFactory {

    public MatchedTrade create(Trade incomingTrade, Trade matchedWith) {
        Trade buyerTrade = "BUYER".equals(incomingTrade.getRole1()) ? incomingTrade : matchedWith;
        Trade sellerTrade = "SELLER".equals(incomingTrade.getRole1()) ? incomingTrade : matchedWith;

        MatchedTrade matched = new MatchedTrade();
        matched.setTrade1Id(buyerTrade.getId());
        matched.setTrade2Id(sellerTrade.getId());
        matched.setCounterparty1(buyerTrade.getCounterparty1());
        matched.setCounterparty2(sellerTrade.getCounterparty1());
        matched.setCurrency1(buyerTrade.getCurrency1());
        matched.setCurrency2(buyerTrade.getCurrency2());
        matched.setValueDate(buyerTrade.getValueDate());
        matched.setStatus(TradeStatus.MATCHED);
        matched.setMatchedAt(Instant.now());
        return matched;
    }
}

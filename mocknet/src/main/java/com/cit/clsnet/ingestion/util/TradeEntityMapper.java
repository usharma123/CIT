package com.cit.clsnet.ingestion.util;

import com.cit.clsnet.ingestion.ParsedTradeMessage;
import com.cit.clsnet.model.Trade;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class TradeEntityMapper {

    public Trade mapToTrade(ParsedTradeMessage parsedTrade) {
        Trade trade = new Trade();
        trade.setTradeId(parsedTrade.tradeId());
        trade.setMessageId(parsedTrade.messageId());
        trade.setCounterparty1(parsedTrade.counterparty1());
        trade.setCounterparty2(parsedTrade.counterparty2());
        trade.setRole1(parsedTrade.role1());
        trade.setRole2(parsedTrade.role2());
        trade.setCurrency1(parsedTrade.currency1());
        trade.setCurrency2(parsedTrade.currency2());
        trade.setAmount1(parsedTrade.amount1());
        trade.setAmount2(parsedTrade.amount2());
        trade.setExchangeRate(parsedTrade.exchangeRate());
        trade.setValueDate(parsedTrade.valueDate());
        trade.setTradeType(parsedTrade.tradeType());
        trade.setRawJson(parsedTrade.rawJson());
        trade.setReceivedAt(Instant.now());
        return trade;
    }
}

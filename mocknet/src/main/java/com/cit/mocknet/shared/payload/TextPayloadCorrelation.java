package com.cit.mocknet.shared.payload;

public record TextPayloadCorrelation(
        String tradeId,
        String messageId,
        String matchedTradeId,
        String nettingSetId,
        String queueName) {

    public static TextPayloadCorrelation empty() {
        return new TextPayloadCorrelation(null, null, null, null, null);
    }
}

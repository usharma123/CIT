package com.cit.clsnet.queue.util;

public record QueuePayloadCorrelation(
        String tradeId,
        String messageId,
        String matchedTradeId,
        String nettingSetId) {
}

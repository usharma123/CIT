package com.cit.clsnet.matching.util;

import com.cit.clsnet.shared.failure.FailureReason;
import com.cit.clsnet.shared.failure.QueueProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class MatchingMessageParser {

    private final ObjectMapper objectMapper;

    public MatchingMessageParser() {
        this.objectMapper = new ObjectMapper();
    }

    public long parseTradeId(String message) {
        JsonNode node;
        try {
            node = objectMapper.readTree(message);
        } catch (Exception e) {
            throw new QueueProcessingException("Invalid matching message payload", e, FailureReason.INVALID_MATCHING_MESSAGE, false);
        }
        if (!node.hasNonNull("tradeId")) {
            throw new QueueProcessingException("Matching message missing tradeId", FailureReason.MISSING_MATCHING_TRADE_ID, false);
        }
        return node.get("tradeId").asLong();
    }
}

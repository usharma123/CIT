package com.cit.clsnet.matching.util;

import com.cit.clsnet.shared.failure.FailureReason;
import com.cit.clsnet.shared.payload.JsonPayloadReader;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

@Component
public class MatchingMessageParser {

    private final JsonPayloadReader jsonPayloadReader;

    public MatchingMessageParser() {
        this.jsonPayloadReader = new JsonPayloadReader();
    }

    public long parseTradeId(String message) {
        JsonNode node = jsonPayloadReader.parse(
                message,
                "Invalid matching message payload",
                FailureReason.INVALID_MATCHING_MESSAGE);
        return jsonPayloadReader.requireLong(
                node,
                "tradeId",
                "Matching message missing tradeId",
                FailureReason.MISSING_MATCHING_TRADE_ID);
    }
}

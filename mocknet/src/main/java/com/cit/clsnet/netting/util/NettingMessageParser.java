package com.cit.clsnet.netting.util;

import com.cit.clsnet.shared.failure.FailureReason;
import com.cit.clsnet.shared.payload.JsonPayloadReader;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

@Component
public class NettingMessageParser {

    private final JsonPayloadReader jsonPayloadReader;

    public NettingMessageParser() {
        this.jsonPayloadReader = new JsonPayloadReader();
    }

    public long parseMatchedTradeId(String message) {
        JsonNode node = jsonPayloadReader.parse(
                message,
                "Failed to process netting message",
                FailureReason.INVALID_NETTING_MESSAGE);
        return jsonPayloadReader.requireLong(
                node,
                "matchedTradeId",
                "Netting message missing matchedTradeId",
                FailureReason.INVALID_NETTING_MESSAGE);
    }
}

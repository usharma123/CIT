package com.cit.mocknet.settlement.util;

import com.cit.mocknet.shared.failure.FailureReason;
import com.cit.mocknet.shared.payload.JsonPayloadReader;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SettlementMessageParser {

    private final JsonPayloadReader jsonPayloadReader;

    public SettlementMessageParser() {
        this.jsonPayloadReader = new JsonPayloadReader();
    }

    public List<Long> parseNettingSetIds(String message) {
        JsonNode node = jsonPayloadReader.parse(
                message,
                "Failed to process settlement message",
                FailureReason.INVALID_SETTLEMENT_MESSAGE);
        return jsonPayloadReader.requireLongArray(
                node,
                "nettingSetIds",
                "Settlement message missing nettingSetIds",
                FailureReason.INVALID_SETTLEMENT_MESSAGE);
    }
}

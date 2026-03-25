package com.cit.clsnet.netting.util;

import com.cit.clsnet.shared.failure.FailureReason;
import com.cit.clsnet.shared.failure.QueueProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class NettingMessageParser {

    private final ObjectMapper objectMapper;

    public NettingMessageParser() {
        this.objectMapper = new ObjectMapper();
    }

    public long parseMatchedTradeId(String message) {
        try {
            JsonNode node = objectMapper.readTree(message);
            if (!node.hasNonNull("matchedTradeId")) {
                throw new QueueProcessingException("Netting message missing matchedTradeId", FailureReason.INVALID_NETTING_MESSAGE, false);
            }
            return node.get("matchedTradeId").asLong();
        } catch (QueueProcessingException e) {
            throw e;
        } catch (Exception e) {
            throw new QueueProcessingException("Failed to process netting message", e, FailureReason.INVALID_NETTING_MESSAGE, false);
        }
    }
}

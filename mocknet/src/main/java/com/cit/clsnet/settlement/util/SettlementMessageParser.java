package com.cit.clsnet.settlement.util;

import com.cit.clsnet.shared.failure.FailureReason;
import com.cit.clsnet.shared.failure.QueueProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class SettlementMessageParser {

    private final ObjectMapper objectMapper;

    public SettlementMessageParser() {
        this.objectMapper = new ObjectMapper();
    }

    public List<Long> parseNettingSetIds(String message) {
        try {
            JsonNode node = objectMapper.readTree(message);
            JsonNode idsNode = node.get("nettingSetIds");
            if (idsNode == null || !idsNode.isArray()) {
                throw new QueueProcessingException("Settlement message missing nettingSetIds", FailureReason.INVALID_SETTLEMENT_MESSAGE, false);
            }

            List<Long> ids = new ArrayList<>();
            for (JsonNode idNode : idsNode) {
                ids.add(idNode.asLong());
            }
            return ids;
        } catch (QueueProcessingException e) {
            throw e;
        } catch (Exception e) {
            throw new QueueProcessingException("Failed to process settlement message", e, FailureReason.INVALID_SETTLEMENT_MESSAGE, false);
        }
    }
}

package com.cit.clsnet.shared.payload;

import com.cit.clsnet.shared.failure.FailureReason;
import com.cit.clsnet.shared.failure.QueueProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class JsonPayloadReader {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public JsonNode parse(String payload, String invalidMessage, FailureReason reason) {
        try {
            return OBJECT_MAPPER.readTree(payload);
        } catch (Exception e) {
            throw new QueueProcessingException(invalidMessage, e, reason, false);
        }
    }

    public JsonNode tryParse(String payload) {
        try {
            return OBJECT_MAPPER.readTree(payload);
        } catch (Exception e) {
            return null;
        }
    }

    public long requireLong(JsonNode node, String fieldName, String missingMessage, FailureReason reason) {
        JsonNode value = node == null ? null : node.get(fieldName);
        if (value == null || value.isNull()) {
            throw new QueueProcessingException(missingMessage, reason, false);
        }
        return value.asLong();
    }

    public List<Long> requireLongArray(JsonNode node, String fieldName, String missingMessage, FailureReason reason) {
        JsonNode arrayNode = node == null ? null : node.get(fieldName);
        if (arrayNode == null || !arrayNode.isArray()) {
            throw new QueueProcessingException(missingMessage, reason, false);
        }

        List<Long> values = new ArrayList<>();
        for (JsonNode value : arrayNode) {
            values.add(value.asLong());
        }
        return values;
    }

    public String findText(JsonNode node, String... fieldNames) {
        if (node == null || node.isNull()) {
            return null;
        }

        if (node.isObject()) {
            for (String fieldName : fieldNames) {
                JsonNode direct = node.get(fieldName);
                if (direct != null && !direct.isNull() && !direct.isContainerNode()) {
                    return trimToNull(direct.asText());
                }
            }

            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                String nested = findText(fields.next().getValue(), fieldNames);
                if (nested != null) {
                    return nested;
                }
            }
            return null;
        }

        if (node.isArray()) {
            for (JsonNode child : node) {
                String nested = findText(child, fieldNames);
                if (nested != null) {
                    return nested;
                }
            }
        }

        return null;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

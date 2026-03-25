package com.cit.clsnet.queue.util;

import com.cit.clsnet.xml.FpmlTradeMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class QueuePayloadCorrelationExtractor {

    private static final Pattern XML_MESSAGE_ID = Pattern.compile("<messageId>([^<]+)</messageId>");
    private static final Pattern XML_TRADE_ID = Pattern.compile("<tradeId>([^<]+)</tradeId>");

    private final ObjectMapper objectMapper;
    private final XmlMapper xmlMapper;

    public QueuePayloadCorrelationExtractor() {
        this.objectMapper = new ObjectMapper();
        this.xmlMapper = new XmlMapper();
    }

    public QueuePayloadCorrelation extract(String payload) {
        String text = payload == null ? "" : payload.trim();
        if (text.isEmpty()) {
            return new QueuePayloadCorrelation(null, null, null, null);
        }

        if (text.startsWith("<")) {
            QueuePayloadCorrelation xmlCorrelation = extractFromXml(text);
            if (xmlCorrelation != null) {
                return xmlCorrelation;
            }
            return extractFromXmlRegex(text);
        }

        if (text.startsWith("{") || text.startsWith("[")) {
            return extractFromJson(text);
        }

        return new QueuePayloadCorrelation(null, null, null, null);
    }

    private QueuePayloadCorrelation extractFromXml(String xml) {
        try {
            FpmlTradeMessage message = xmlMapper.readValue(xml, FpmlTradeMessage.class);
            String messageId = message.getHeader() == null ? null : trimToNull(message.getHeader().getMessageId());
            String tradeId = message.getTrade() == null ? null : trimToNull(message.getTrade().getTradeId());
            return new QueuePayloadCorrelation(tradeId, messageId, null, null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private QueuePayloadCorrelation extractFromXmlRegex(String xml) {
        String messageId = null;
        Matcher messageMatcher = XML_MESSAGE_ID.matcher(xml);
        if (messageMatcher.find()) {
            messageId = trimToNull(messageMatcher.group(1));
        }

        String tradeId = null;
        Matcher tradeMatcher = XML_TRADE_ID.matcher(xml);
        if (tradeMatcher.find()) {
            tradeId = trimToNull(tradeMatcher.group(1));
        }
        return new QueuePayloadCorrelation(tradeId, messageId, null, null);
    }

    private QueuePayloadCorrelation extractFromJson(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            return new QueuePayloadCorrelation(
                    findText(root, "tradeId"),
                    findText(root, "messageId"),
                    findText(root, "matchedTradeId"),
                    findText(root, "nettingSetId"));
        } catch (Exception ignored) {
            return new QueuePayloadCorrelation(null, null, null, null);
        }
    }

    private String findText(JsonNode node, String fieldName) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            JsonNode direct = node.get(fieldName);
            if (direct != null && !direct.isNull()) {
                return trimToNull(direct.asText());
            }
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                String nested = findText(fields.next().getValue(), fieldName);
                if (nested != null) {
                    return nested;
                }
            }
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                String nested = findText(child, fieldName);
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

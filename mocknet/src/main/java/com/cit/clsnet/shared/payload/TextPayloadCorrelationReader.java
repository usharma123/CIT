package com.cit.clsnet.shared.payload;

import com.cit.clsnet.xml.FpmlTradeMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TextPayloadCorrelationReader {

    private static final Pattern XML_MESSAGE_ID = Pattern.compile("<messageId>([^<]+)</messageId>");
    private static final Pattern XML_TRADE_ID = Pattern.compile("<tradeId>([^<]+)</tradeId>");

    private final JsonPayloadReader jsonPayloadReader;
    private final XmlMapper xmlMapper;

    public TextPayloadCorrelationReader() {
        this.jsonPayloadReader = new JsonPayloadReader();
        this.xmlMapper = new XmlMapper();
    }

    public TextPayloadCorrelation extract(String payload) {
        String text = payload == null ? "" : payload.trim();
        if (text.isEmpty()) {
            return TextPayloadCorrelation.empty();
        }

        if (text.startsWith("<")) {
            TextPayloadCorrelation xmlCorrelation = extractFromXml(text);
            return xmlCorrelation != null ? xmlCorrelation : extractFromXmlRegex(text);
        }

        if (text.startsWith("{") || text.startsWith("[")) {
            JsonNode root = jsonPayloadReader.tryParse(text);
            if (root == null) {
                return TextPayloadCorrelation.empty();
            }
            return new TextPayloadCorrelation(
                    jsonPayloadReader.findText(root, "tradeId", "trade.id", "trade_id"),
                    jsonPayloadReader.findText(root, "messageId", "message.id", "message_id"),
                    jsonPayloadReader.findText(root, "matchedTradeId", "matched.trade.id"),
                    jsonPayloadReader.findText(root, "nettingSetId", "netting.set.id"),
                    jsonPayloadReader.findText(root, "queueName", "queue.name", "queue"));
        }

        return TextPayloadCorrelation.empty();
    }

    private TextPayloadCorrelation extractFromXml(String xml) {
        try {
            FpmlTradeMessage message = xmlMapper.readValue(xml, FpmlTradeMessage.class);
            String messageId = message.getHeader() == null ? null : trimToNull(message.getHeader().getMessageId());
            String tradeId = message.getTrade() == null ? null : trimToNull(message.getTrade().getTradeId());
            return new TextPayloadCorrelation(tradeId, messageId, null, null, null);
        } catch (Exception e) {
            return null;
        }
    }

    private TextPayloadCorrelation extractFromXmlRegex(String xml) {
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

        return new TextPayloadCorrelation(tradeId, messageId, null, null, null);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

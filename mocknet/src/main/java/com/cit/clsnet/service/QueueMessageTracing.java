package com.cit.clsnet.service;

import com.cit.clsnet.model.QueueMessage;
import com.cit.clsnet.xml.FpmlTradeMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class QueueMessageTracing {

    private static final AttributeKey<String> FAILURE_REASON_CODE = AttributeKey.stringKey("failure.reason_code");
    private static final AttributeKey<String> MATCHED_TRADE_ID = AttributeKey.stringKey("matched.trade.id");
    private static final AttributeKey<String> MESSAGE_ID = AttributeKey.stringKey("message.id");
    private static final AttributeKey<String> NETTING_SET_ID = AttributeKey.stringKey("netting.set.id");
    private static final AttributeKey<String> PROCESSING_OUTCOME = AttributeKey.stringKey("processing.outcome");
    private static final AttributeKey<Long> QUEUE_MESSAGE_ID = AttributeKey.longKey("queue.message.id");
    private static final AttributeKey<String> QUEUE_NAME = AttributeKey.stringKey("queue.name");
    private static final AttributeKey<String> TRADE_ID = AttributeKey.stringKey("trade.id");
    private static final AttributeKey<String> WORKER_NAME = AttributeKey.stringKey("worker.name");
    private static final Pattern XML_MESSAGE_ID = Pattern.compile("<messageId>([^<]+)</messageId>");
    private static final Pattern XML_TRADE_ID = Pattern.compile("<tradeId>([^<]+)</tradeId>");

    private final Tracer tracer;
    private final ObjectMapper objectMapper;
    private final XmlMapper xmlMapper;

    public QueueMessageTracing(OpenTelemetry openTelemetry) {
        this.tracer = openTelemetry.getTracer("com.cit.clsnet.queue-processing");
        this.objectMapper = new ObjectMapper();
        this.xmlMapper = new XmlMapper();
    }

    public Span startProcessingSpan(QueueMessage message) {
        Span span = tracer.spanBuilder("QueueMessage.process")
                .setNoParent()
                .setSpanKind(SpanKind.CONSUMER)
                .startSpan();

        if (message.getId() != null) {
            span.setAttribute(QUEUE_MESSAGE_ID, message.getId());
        }
        if (message.getQueueName() != null) {
            span.setAttribute(QUEUE_NAME, message.getQueueName().name());
        }
        if (message.getWorkerName() != null) {
            span.setAttribute(WORKER_NAME, message.getWorkerName());
        }

        applyPayloadCorrelation(span, message.getPayload());
        return span;
    }

    public void markOutcome(Span span, String outcome) {
        span.setAttribute(PROCESSING_OUTCOME, outcome);
    }

    public void markFailure(Span span, FailureContext failureContext, QueueFailureDisposition disposition) {
        span.setStatus(StatusCode.ERROR, failureContext.getMessage());
        span.setAttribute(PROCESSING_OUTCOME, disposition == QueueFailureDisposition.RETRIED ? "retried" : "failed");
        if (disposition == QueueFailureDisposition.FAILED) {
            span.setAttribute(FAILURE_REASON_CODE, failureContext.getReasonCode());
        }
    }

    private void applyPayloadCorrelation(Span span, String payload) {
        String text = payload == null ? "" : payload.trim();
        if (text.isEmpty()) {
            return;
        }

        if (text.startsWith("<")) {
            if (!applyXmlCorrelation(span, text)) {
                applyXmlRegexFallback(span, text);
            }
            return;
        }

        if (text.startsWith("{") || text.startsWith("[")) {
            applyJsonCorrelation(span, text);
        }
    }

    private boolean applyXmlCorrelation(Span span, String xml) {
        try {
            FpmlTradeMessage message = xmlMapper.readValue(xml, FpmlTradeMessage.class);
            if (message.getHeader() != null && notBlank(message.getHeader().getMessageId())) {
                span.setAttribute(MESSAGE_ID, message.getHeader().getMessageId().trim());
            }
            if (message.getTrade() != null && notBlank(message.getTrade().getTradeId())) {
                span.setAttribute(TRADE_ID, message.getTrade().getTradeId().trim());
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void applyXmlRegexFallback(Span span, String xml) {
        Matcher messageMatcher = XML_MESSAGE_ID.matcher(xml);
        if (messageMatcher.find()) {
            span.setAttribute(MESSAGE_ID, messageMatcher.group(1).trim());
        }

        Matcher tradeMatcher = XML_TRADE_ID.matcher(xml);
        if (tradeMatcher.find()) {
            span.setAttribute(TRADE_ID, tradeMatcher.group(1).trim());
        }
    }

    private void applyJsonCorrelation(Span span, String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            String matchedTradeId = findText(root, "matchedTradeId");
            if (notBlank(matchedTradeId)) {
                span.setAttribute(MATCHED_TRADE_ID, matchedTradeId);
            }

            String nettingSetId = findText(root, "nettingSetId");
            if (notBlank(nettingSetId)) {
                span.setAttribute(NETTING_SET_ID, nettingSetId);
            }

            String messageId = findText(root, "messageId");
            if (notBlank(messageId)) {
                span.setAttribute(MESSAGE_ID, messageId);
            }
        } catch (Exception ignored) {
            // Best-effort correlation only.
        }
    }

    private String findText(JsonNode node, String fieldName) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            JsonNode direct = node.get(fieldName);
            if (direct != null && !direct.isNull()) {
                return direct.asText();
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

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}

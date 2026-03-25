package com.cit.clsnet.config;

import com.cit.clsnet.model.MatchedTrade;
import com.cit.clsnet.model.NettingSet;
import com.cit.clsnet.model.QueueMessage;
import com.cit.clsnet.model.QueueName;
import com.cit.clsnet.model.SettlementInstruction;
import com.cit.clsnet.model.Trade;
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
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.data.repository.Repository;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;

@Aspect
@Component
public class ComponentTracingAspect {

    private static final AttributeKey<String> COMPONENT_CLASS = AttributeKey.stringKey("component.class");
    private static final AttributeKey<String> COMPONENT_KIND = AttributeKey.stringKey("component.kind");
    private static final AttributeKey<String> COMPONENT_METHOD = AttributeKey.stringKey("component.method");
    private static final AttributeKey<String> CODE_NAMESPACE = AttributeKey.stringKey("code.namespace");
    private static final AttributeKey<String> CLS_STAGE = AttributeKey.stringKey("cls.stage");
    private static final AttributeKey<String> ERROR_TYPE = AttributeKey.stringKey("error.type");
    private static final AttributeKey<String> MATCHED_TRADE_ID = AttributeKey.stringKey("matched.trade.id");
    private static final AttributeKey<String> MESSAGE_ID = AttributeKey.stringKey("message.id");
    private static final AttributeKey<String> MESSAGE_IDS = AttributeKey.stringKey("message.ids");
    private static final AttributeKey<String> NETTING_SET_ID = AttributeKey.stringKey("netting.set.id");
    private static final AttributeKey<String> QUEUE_NAME = AttributeKey.stringKey("queue.name");
    private static final AttributeKey<String> TRADE_ID = AttributeKey.stringKey("trade.id");
    private static final AttributeKey<String> TRADE_IDS = AttributeKey.stringKey("trade.ids");
    private static final AttributeKey<String> TRADE_RECORD_ID = AttributeKey.stringKey("trade.record.id");

    private final Tracer tracer;
    private final ObjectMapper objectMapper;
    private final XmlMapper xmlMapper;

    public ComponentTracingAspect(OpenTelemetry openTelemetry) {
        this.tracer = openTelemetry.getTracer("com.cit.clsnet.component-tracing");
        this.objectMapper = new ObjectMapper();
        this.xmlMapper = new XmlMapper();
    }

    @Around(
            "execution(public * com.cit.clsnet..*.*(..))"
                    + " && !within(com.cit.clsnet.config..*)"
                    + " && !@within(org.springframework.context.annotation.Configuration)"
                    + " && !@within(org.springframework.boot.test.context.TestConfiguration)"
                    + " && !execution(@org.springframework.context.annotation.Bean * *(..))"
    )
    public Object traceComponent(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Class<?> declaringType = signature.getDeclaringType();
        Class<?> targetType = resolveTargetType(joinPoint, declaringType);
        String componentKind = resolveComponentKind(declaringType, targetType);
        if (componentKind == null) {
            return joinPoint.proceed();
        }
        String stage = resolveStage(declaringType, targetType, componentKind);
        String componentClass = declaringType.getSimpleName();
        String methodName = signature.getName();

        Span span = tracer.spanBuilder(componentClass + "." + methodName)
                .setSpanKind("controller".equals(componentKind) ? SpanKind.SERVER : SpanKind.INTERNAL)
                .startSpan();

        span.setAttribute(COMPONENT_CLASS, componentClass);
        span.setAttribute(COMPONENT_KIND, componentKind);
        span.setAttribute(COMPONENT_METHOD, methodName);
        span.setAttribute(CODE_NAMESPACE, declaringType.getName());
        span.setAttribute(CLS_STAGE, stage);
        applyCorrelation(span, joinPoint.getArgs());

        try {
            Object result;
            try (var scope = span.makeCurrent()) {
                result = joinPoint.proceed();
            }
            applyCorrelation(span, result);
            return result;
        } catch (Throwable error) {
            span.recordException(error);
            span.setStatus(StatusCode.ERROR, error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
            span.setAttribute(ERROR_TYPE, error.getClass().getName());
            throw error;
        } finally {
            span.end();
        }
    }

    private void applyCorrelation(Span span, Object value) {
        CorrelationTags tags = new CorrelationTags();
        collectCorrelation(tags, value);

        if (!tags.tradeIds.isEmpty()) {
            span.setAttribute(TRADE_ID, tags.tradeIds.iterator().next());
            span.setAttribute(TRADE_IDS, String.join(",", tags.tradeIds));
        }
        if (!tags.messageIds.isEmpty()) {
            span.setAttribute(MESSAGE_ID, tags.messageIds.iterator().next());
            span.setAttribute(MESSAGE_IDS, String.join(",", tags.messageIds));
        }
        if (!tags.queueNames.isEmpty()) {
            span.setAttribute(QUEUE_NAME, tags.queueNames.iterator().next());
        }
        if (!tags.tradeRecordIds.isEmpty()) {
            span.setAttribute(TRADE_RECORD_ID, tags.tradeRecordIds.iterator().next());
        }
        if (!tags.matchedTradeIds.isEmpty()) {
            span.setAttribute(MATCHED_TRADE_ID, tags.matchedTradeIds.iterator().next());
        }
        if (!tags.nettingSetIds.isEmpty()) {
            span.setAttribute(NETTING_SET_ID, tags.nettingSetIds.iterator().next());
        }
    }

    private void collectCorrelation(CorrelationTags tags, Object value) {
        if (value == null) {
            return;
        }

        if (value instanceof Object[] values) {
            for (Object item : values) {
                collectCorrelation(tags, item);
            }
            return;
        }

        if (value instanceof Optional<?> optional) {
            optional.ifPresent(item -> collectCorrelation(tags, item));
            return;
        }

        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                collectCorrelation(tags, item);
            }
            return;
        }

        if (value instanceof Iterator<?> iterator) {
            while (iterator.hasNext()) {
                collectCorrelation(tags, iterator.next());
            }
            return;
        }

        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof String key) {
                    collectJsonField(tags, key, entry.getValue());
                }
                collectCorrelation(tags, entry.getValue());
            }
            return;
        }

        if (value instanceof QueueMessage queueMessage) {
            if (queueMessage.getQueueName() != null) {
                tags.queueNames.add(queueMessage.getQueueName().name());
            }
            collectCorrelation(tags, queueMessage.getPayload());
            return;
        }

        if (value instanceof QueueName queueName) {
            tags.queueNames.add(queueName.name());
            return;
        }

        if (value instanceof Trade trade) {
            addIfPresent(tags.tradeIds, trade.getTradeId());
            addIfPresent(tags.messageIds, trade.getMessageId());
            addIfPresent(tags.tradeRecordIds, trade.getId());
            return;
        }

        if (value instanceof MatchedTrade matchedTrade) {
            addIfPresent(tags.matchedTradeIds, matchedTrade.getId());
            return;
        }

        if (value instanceof NettingSet nettingSet) {
            addIfPresent(tags.nettingSetIds, nettingSet.getId());
            addIfPresent(tags.matchedTradeIds, nettingSet.getMatchedTradeId());
            return;
        }

        if (value instanceof SettlementInstruction instruction) {
            addIfPresent(tags.nettingSetIds, instruction.getNettingSetId());
            return;
        }

        if (value instanceof CharSequence sequence) {
            collectFromText(tags, sequence.toString());
        }
    }

    private void collectFromText(CorrelationTags tags, String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            return;
        }

        if (text.startsWith("<")) {
            try {
                FpmlTradeMessage message = xmlMapper.readValue(text, FpmlTradeMessage.class);
                if (message.getTrade() != null) {
                    addIfPresent(tags.tradeIds, message.getTrade().getTradeId());
                }
                if (message.getHeader() != null) {
                    addIfPresent(tags.messageIds, message.getHeader().getMessageId());
                }
                return;
            } catch (Exception ignored) {
                return;
            }
        }

        if (text.startsWith("{") || text.startsWith("[")) {
            try {
                JsonNode node = objectMapper.readTree(text);
                collectFromJson(tags, node);
            } catch (Exception ignored) {
                return;
            }
        }
    }

    private void collectFromJson(CorrelationTags tags, JsonNode node) {
        if (node == null || node.isNull()) {
            return;
        }

        if (node.isArray()) {
            for (JsonNode item : node) {
                collectFromJson(tags, item);
            }
            return;
        }

        if (!node.isObject()) {
            return;
        }

        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            collectJsonField(tags, field.getKey(), field.getValue());
            collectFromJson(tags, field.getValue());
        }
    }

    private void collectJsonField(CorrelationTags tags, String key, Object value) {
        if (value == null) {
            return;
        }

        String normalized = key.trim();
        if (normalized.isEmpty()) {
            return;
        }

        String stringValue = value instanceof JsonNode node && !node.isContainerNode()
                ? node.asText()
                : String.valueOf(value);
        if (stringValue == null || stringValue.isBlank()) {
            return;
        }

        switch (normalized) {
            case "tradeId", "trade.id", "trade_id" -> {
                if (looksLikeBusinessId(stringValue)) {
                    addIfPresent(tags.tradeIds, stringValue);
                } else {
                    addIfPresent(tags.tradeRecordIds, stringValue);
                }
            }
            case "messageId", "message.id", "message_id" -> addIfPresent(tags.messageIds, stringValue);
            case "queueName", "queue.name", "queue" -> addIfPresent(tags.queueNames, stringValue);
            case "matchedTradeId", "matched.trade.id" -> addIfPresent(tags.matchedTradeIds, stringValue);
            case "nettingSetId", "netting.set.id" -> addIfPresent(tags.nettingSetIds, stringValue);
            default -> {
            }
        }
    }

    private boolean looksLikeBusinessId(String value) {
        return value.chars().anyMatch(Character::isLetter);
    }

    private Class<?> resolveTargetType(ProceedingJoinPoint joinPoint, Class<?> declaringType) {
        Object target = joinPoint.getTarget();
        if (target == null) {
            return declaringType;
        }
        return AopUtils.getTargetClass(target);
    }

    private String resolveComponentKind(Class<?> declaringType, Class<?> targetType) {
        if (AnnotatedElementUtils.hasAnnotation(declaringType, RestController.class)
                || AnnotatedElementUtils.hasAnnotation(targetType, RestController.class)) {
            return "controller";
        }
        if (isRepository(declaringType) || isRepository(targetType)) {
            return "repository";
        }
        if (AnnotatedElementUtils.hasAnnotation(declaringType, Service.class)
                || AnnotatedElementUtils.hasAnnotation(targetType, Service.class)
                || AnnotatedElementUtils.hasAnnotation(declaringType, Component.class)
                || AnnotatedElementUtils.hasAnnotation(targetType, Component.class)) {
            return "service";
        }
        return null;
    }

    private boolean isRepository(Class<?> type) {
        if (type == null) {
            return false;
        }
        if (Repository.class.isAssignableFrom(type)) {
            return true;
        }
        for (Class<?> implemented : type.getInterfaces()) {
            if (isRepository(implemented)) {
                return true;
            }
        }
        return false;
    }

    private String resolveStage(Class<?> declaringType, Class<?> targetType, String componentKind) {
        if ("controller".equals(componentKind)) {
            return "HTTP";
        }
        if ("repository".equals(componentKind)) {
            return "DATABASE";
        }
        String typeName = (declaringType.getName() + " " + targetType.getName()).toLowerCase();
        if (typeName.contains("settlement") || typeName.contains("twophase")) {
            return "SETTLEMENT";
        }
        if (typeName.contains("ingestion")) {
            return "INGESTION";
        }
        if (typeName.contains("matching")) {
            return "MATCHING";
        }
        if (typeName.contains("netting")) {
            return "NETTING";
        }
        return "OTHER";
    }

    private void addIfPresent(LinkedHashSet<String> target, Object value) {
        if (value == null) {
            return;
        }
        String stringValue = String.valueOf(value).trim();
        if (!stringValue.isEmpty()) {
            target.add(stringValue);
        }
    }

    private static final class CorrelationTags {
        private final LinkedHashSet<String> tradeIds = new LinkedHashSet<>();
        private final LinkedHashSet<String> messageIds = new LinkedHashSet<>();
        private final LinkedHashSet<String> queueNames = new LinkedHashSet<>();
        private final LinkedHashSet<String> tradeRecordIds = new LinkedHashSet<>();
        private final LinkedHashSet<String> matchedTradeIds = new LinkedHashSet<>();
        private final LinkedHashSet<String> nettingSetIds = new LinkedHashSet<>();
    }
}

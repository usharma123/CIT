package com.cit.mocknet.queue;

import com.cit.mocknet.model.QueueMessage;
import com.cit.mocknet.observability.ProcessingContext;
import com.cit.mocknet.observability.OperationalTelemetry;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.MDC;
import com.cit.mocknet.queue.util.QueuePayloadCorrelation;
import com.cit.mocknet.queue.util.QueuePayloadCorrelationExtractor;
import com.cit.mocknet.shared.failure.FailureContext;
import com.cit.mocknet.shared.failure.QueueFailureDisposition;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

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

    @Autowired(required = false) private OperationalTelemetry telemetry;
    private final Tracer tracer;
    private final QueuePayloadCorrelationExtractor correlationExtractor;

    public QueueMessageTracing(OpenTelemetry openTelemetry, QueuePayloadCorrelationExtractor correlationExtractor) {
        this.tracer = openTelemetry.getTracer("com.cit.mocknet.queue-processing");
        this.correlationExtractor = correlationExtractor;
    }

    public Span startProcessingSpan(QueueMessage message) {
        var spanBuilder = tracer.spanBuilder("QueueMessage.process")
                .setSpanKind(SpanKind.CONSUMER);

        spanBuilder.setParent(W3CTraceContextPropagator.getInstance().extract(
                Context.root(), message, new TextMapGetter<QueueMessage>() {
                    public Iterable<String> keys(QueueMessage carrier) { return List.of("traceparent", "tracestate"); }
                    public String get(QueueMessage carrier, String key) {
                        return "traceparent".equals(key) ? carrier.getTraceContext()
                                : "tracestate".equals(key) ? carrier.getTraceState() : null;
                    }
                }));
        Span span = spanBuilder.startSpan();
        ProcessingContext.set(message);
        if (message.getOperationId() != null) {
            span.setAttribute("operation.id", message.getOperationId());
            MDC.put("operation_id", message.getOperationId());
        }
        if (message.getBusinessId() != null) {
            span.setAttribute("business.id", message.getBusinessId());
            MDC.put("business_id", message.getBusinessId());
        }
        if (telemetry != null) {
            try { telemetry.attachTrace(message, span); }
            catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Could not persist trace correlation for queue message {}", message.getId(), e);
            }
        }
        if (!span.isRecording()) return span;
        span.setAttribute("messaging.system", "mocknet");
        span.setAttribute("messaging.operation.type", "process");
        span.setAttribute("queue.attempt", message.getAttempts() + 1L);
        if (message.getAvailableAt() != null) {
            span.setAttribute("queue.wait_ms", Math.max(0, Duration.between(
                    message.getAvailableAt(), message.getClaimedAt() == null ? Instant.now() : message.getClaimedAt()).toMillis()));
        }
        if (message.getId() != null) {
            span.setAttribute(QUEUE_MESSAGE_ID, message.getId());
        }
        if (message.getQueueName() != null) {
            span.setAttribute(QUEUE_NAME, message.getQueueName().name());
            span.setAttribute("messaging.destination.name", message.getQueueName().name());
            span.setAttribute("component.stage", message.getQueueName().name());
        }
        if (message.getWorkerName() != null) {
            span.setAttribute(WORKER_NAME, message.getWorkerName());
        }

        applyPayloadCorrelation(span, message.getPayload());
        return span;
    }

    public void endProcessingSpan(Span span) {
        try { span.end(); } finally {
            ProcessingContext.clear();
            MDC.remove("operation_id");
            MDC.remove("business_id");
        }
    }

    public void markOutcome(Span span, String outcome) {
        span.setAttribute(PROCESSING_OUTCOME, outcome);
    }

    public void markFailure(Span span, FailureContext failureContext, QueueFailureDisposition disposition) {
        span.setStatus(StatusCode.ERROR, failureContext.getMessage());
        span.setAttribute(PROCESSING_OUTCOME, disposition == QueueFailureDisposition.RETRIED ? "retried" : "failed");
        span.setAttribute(FAILURE_REASON_CODE, failureContext.getReasonCode());
        span.setAttribute("failure.retryable", failureContext.isRetryable());
        span.setAttribute("error.type", failureContext.getReasonCode());
    }

    private void applyPayloadCorrelation(Span span, String payload) {
        if (payload != null && payload.length() > 65536) return;
        QueuePayloadCorrelation correlation = correlationExtractor.extract(payload);
        if (correlation.tradeId() != null) {
            span.setAttribute(TRADE_ID, correlation.tradeId());
        }
        if (correlation.messageId() != null) {
            span.setAttribute(MESSAGE_ID, correlation.messageId());
        }
        if (correlation.matchedTradeId() != null) {
            span.setAttribute(MATCHED_TRADE_ID, correlation.matchedTradeId());
        }
        if (correlation.nettingSetId() != null) {
            span.setAttribute(NETTING_SET_ID, correlation.nettingSetId());
        }
    }

}

package com.cit.mocknet.queue;

import com.cit.mocknet.model.QueueMessage;
import com.cit.mocknet.queue.util.QueuePayloadCorrelation;
import com.cit.mocknet.queue.util.QueuePayloadCorrelationExtractor;
import com.cit.mocknet.shared.failure.FailureContext;
import com.cit.mocknet.shared.failure.QueueFailureDisposition;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
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

    private final Tracer tracer;
    private final QueuePayloadCorrelationExtractor correlationExtractor;

    public QueueMessageTracing(OpenTelemetry openTelemetry, QueuePayloadCorrelationExtractor correlationExtractor) {
        this.tracer = openTelemetry.getTracer("com.cit.mocknet.queue-processing");
        this.correlationExtractor = correlationExtractor;
    }

    public Span startProcessingSpan(QueueMessage message) {
        var spanBuilder = tracer.spanBuilder("QueueMessage.process")
                .setSpanKind(SpanKind.CONSUMER);

        Context parentContext = extractParentContext(message.getTraceContext());
        if (parentContext != null) {
            spanBuilder.setParent(parentContext);
        } else {
            spanBuilder.setNoParent();
        }

        Span span = spanBuilder.startSpan();

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

    private Context extractParentContext(String traceparent) {
        if (traceparent == null || traceparent.isEmpty()) {
            return null;
        }
        // Parse W3C traceparent: version-traceId-spanId-traceFlags
        String[] parts = traceparent.split("-");
        if (parts.length < 4) {
            return null;
        }
        try {
            String traceId = parts[1];
            String spanId = parts[2];
            byte flags = (byte) Integer.parseInt(parts[3], 16);

            SpanContext remoteContext = SpanContext.createFromRemoteParent(
                    traceId,
                    spanId,
                    TraceFlags.fromByte(flags),
                    TraceState.getDefault());

            return Context.root().with(Span.wrap(remoteContext));
        } catch (Exception e) {
            return null;
        }
    }
}

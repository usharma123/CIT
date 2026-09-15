package com.cit.mocknet.queue;

import com.cit.mocknet.model.QueueMessage;
import com.cit.mocknet.model.QueueName;
import com.cit.mocknet.queue.util.QueuePayloadCorrelationExtractor;
import com.cit.mocknet.shared.failure.FailureContext;
import com.cit.mocknet.shared.failure.FailureReason;
import com.cit.mocknet.shared.failure.QueueFailureDisposition;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class QueueMessageTracingTest {
    @Test
    void preservesParentTraceStateAndRetryDiagnostics() {
        var exporter = InMemorySpanExporter.create();
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            var tracing = new QueueMessageTracing(OpenTelemetrySdk.builder().setTracerProvider(provider).build(), new QueuePayloadCorrelationExtractor());
            var message = message("00-0123456789abcdef0123456789abcdef-0123456789abcdef-01");
            message.setTraceState("vendor=value");
            message.setAttempts(1);
            message.setAvailableAt(Instant.parse("2026-01-01T00:00:00Z"));
            message.setClaimedAt(Instant.parse("2026-01-01T00:00:00.250Z"));
            var span = tracing.startProcessingSpan(message);
            tracing.markFailure(span, FailureContext.of("retry", FailureReason.CONCURRENCY_CONFLICT, true), QueueFailureDisposition.RETRIED);
            span.end();
            var data = exporter.getFinishedSpanItems().get(0);
            assertEquals("0123456789abcdef0123456789abcdef", data.getTraceId());
            assertEquals("0123456789abcdef", data.getParentSpanId());
            assertEquals("value", data.getSpanContext().getTraceState().get("vendor"));
            assertEquals(SpanKind.CONSUMER, data.getKind());
            assertEquals(2L, data.getAttributes().get(AttributeKey.longKey("queue.attempt")));
            assertEquals(250L, data.getAttributes().get(AttributeKey.longKey("queue.wait_ms")));
            assertEquals("concurrency_conflict", data.getAttributes().get(AttributeKey.stringKey("failure.reason_code")));
        }
    }

    @Test
    void invalidContextDoesNotInheritWorkerContextAndUnsampledParentsStayUnsampled() {
        try (var provider = SdkTracerProvider.builder().setSampler(Sampler.parentBased(Sampler.alwaysOn())).build()) {
            var sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
            var tracing = new QueueMessageTracing(sdk, new QueuePayloadCorrelationExtractor());
            Span worker = sdk.getTracer("test").spanBuilder("worker").startSpan();
            try (var ignored = worker.makeCurrent()) {
                for (String invalid : new String[]{"garbage", "ff-0123456789abcdef0123456789abcdef-0123456789abcdef-01", "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01-extra"}) {
                    var span = tracing.startProcessingSpan(message(invalid));
                    assertNotEquals(worker.getSpanContext().getTraceId(), span.getSpanContext().getTraceId());
                    assertNotEquals("0123456789abcdef0123456789abcdef", span.getSpanContext().getTraceId());
                    span.end();
                }
                var unsampled = tracing.startProcessingSpan(message("00-0123456789abcdef0123456789abcdef-0123456789abcdef-00"));
                assertFalse(unsampled.isRecording());
                unsampled.end();
            } finally { worker.end(); }
        }
    }

    private QueueMessage message(String context) {
        var message = new QueueMessage();
        message.setTraceContext(context);
        message.setQueueName(QueueName.INGESTION);
        message.setPayload("{}");
        return message;
    }
}

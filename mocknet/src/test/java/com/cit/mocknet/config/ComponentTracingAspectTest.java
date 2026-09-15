package com.cit.mocknet.config;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ComponentTracingAspectTest {
    @RestController
    static class ExampleController { public Object read(Object value) { return value; } }

    @Test
    void correlationDoesNotConsumeIteratorsOrRecurseForever() throws Throwable {
        var exporter = InMemorySpanExporter.create();
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            var aspect = new ComponentTracingAspect(OpenTelemetrySdk.builder().setTracerProvider(provider).build());
            var point = mock(ProceedingJoinPoint.class);
            var signature = mock(MethodSignature.class);
            when(point.getSignature()).thenReturn(signature);
            when(signature.getDeclaringType()).thenReturn(ExampleController.class);
            when(signature.getName()).thenReturn("read");
            when(signature.getMethod()).thenReturn(ExampleController.class.getMethod("read", Object.class));
            when(point.getTarget()).thenReturn(new ExampleController());
            var iterator = List.of("first", "second").iterator();
            var cycle = new ArrayList<>(); cycle.add(cycle);
            when(point.getArgs()).thenReturn(new Object[]{iterator, cycle});
            when(point.proceed()).thenReturn(iterator);
            assertSame(iterator, aspect.traceComponent(point));
            assertEquals("first", iterator.next());
            assertEquals(1, exporter.getFinishedSpanItems().size());
            var brokenCollection = new java.util.AbstractCollection<Object>() {
                public int size() { return 1; }
                public java.util.Iterator<Object> iterator() { throw new IllegalStateException("unavailable metadata"); }
            };
            when(point.getArgs()).thenReturn(new Object[]{brokenCollection});
            when(point.proceed()).thenReturn("ok");
            assertEquals("ok", aspect.traceComponent(point));
            assertEquals(2, exporter.getFinishedSpanItems().size());
        }
    }

    @org.springframework.stereotype.Service
    static class BusinessStep {
        @TraceBoundary public void process() {}
        public void helper() {}
    }

    @Test
    void conciseTracingKeepsBoundaryParentErrorsAndActualQueueStage() throws Throwable {
        var exporter = InMemorySpanExporter.create();
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            var telemetry = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
            var aspect = new ComponentTracingAspect(telemetry);
            var parent = telemetry.getTracer("test").spanBuilder("queue attempt").startSpan();
            var point = mock(ProceedingJoinPoint.class);
            var signature = mock(MethodSignature.class);
            when(point.getSignature()).thenReturn(signature);
            when(signature.getDeclaringType()).thenReturn(BusinessStep.class);
            when(point.getTarget()).thenReturn(new BusinessStep());
            when(point.getArgs()).thenReturn(new Object[0]);
            var message = new com.cit.mocknet.model.QueueMessage();
            message.setQueueName(com.cit.mocknet.model.QueueName.NETTING);
            com.cit.mocknet.observability.ProcessingContext.set(message);
            try (var scope = parent.makeCurrent()) {
                when(signature.getName()).thenReturn("helper");
                when(signature.getMethod()).thenReturn(BusinessStep.class.getMethod("helper"));
                aspect.traceComponent(point);
                assertTrue(exporter.getFinishedSpanItems().isEmpty());
                when(signature.getName()).thenReturn("process");
                when(signature.getMethod()).thenReturn(BusinessStep.class.getMethod("process"));
                var failure = new IllegalStateException("boundary failure");
                when(point.proceed()).thenThrow(failure);
                assertSame(failure, assertThrows(IllegalStateException.class, () -> aspect.traceComponent(point)));
                var span = exporter.getFinishedSpanItems().get(0);
                assertEquals(parent.getSpanContext().getSpanId(), span.getParentSpanId());
                assertEquals("NETTING", span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("component.stage")));
                assertEquals(io.opentelemetry.api.trace.StatusCode.ERROR, span.getStatus().getStatusCode());
                assertEquals(1, span.getEvents().size());
            } finally {
                com.cit.mocknet.observability.ProcessingContext.clear();
                parent.end();
            }
        }
    }
}

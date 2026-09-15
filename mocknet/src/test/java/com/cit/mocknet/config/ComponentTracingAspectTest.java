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
}

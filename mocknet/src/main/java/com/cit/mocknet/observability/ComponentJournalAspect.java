package com.cit.mocknet.observability;

import com.cit.mocknet.model.QueueMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import com.cit.mocknet.model.QueueName;

/** Structured component evidence for Approach C. No OpenTelemetry dependency. */
@Aspect
@Component
@Order(0)
@ConditionalOnProperty(name = "mocknet.component-journal.enabled", havingValue = "true")
public class ComponentJournalAspect {
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger("mocknet.component.journal");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BOOT = UUID.randomUUID().toString();
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final ThreadLocal<ArrayDeque<String>> CALLS = ThreadLocal.withInitial(ArrayDeque::new);
    @Value("${mocknet.instance:local-1}") private String instance;

    @Around("execution(public * com.cit.mocknet.queue.QueueBroker.claimNext(..))")
    public Object recordClaimFailure(ProceedingJoinPoint point) throws Throwable {
        Instant started = Instant.now();
        long clock = System.nanoTime();
        try { return point.proceed(); }
        catch (Throwable error) {
            QueueMessage context = new QueueMessage();
            context.setQueueName((QueueName) point.getArgs()[0]);
            write("failure", UUID.randomUUID().toString(), null, 0, "QueueBroker.claimNext",
                    context, null, (System.nanoTime()-clock)/1e6, error, started);
            throw error;
        }
    }

    @Around("execution(public * com.cit.mocknet.ingestion..*(..))"
            + " || execution(public * com.cit.mocknet.matching..*(..))"
            + " || execution(public * com.cit.mocknet.netting..*(..))"
            + " || execution(public * com.cit.mocknet.settlement..*(..))"
            + " || execution(public * com.cit.mocknet.queue.QueueBroker.publish(..))")
    public Object record(ProceedingJoinPoint point) throws Throwable {
        QueueMessage message = ProcessingContext.current();
        String component = point.getSignature().getDeclaringType().getSimpleName() + "." + point.getSignature().getName();
        if (message == null && !component.equals("QueueBroker.publish")
                && !component.equals("TradeSubmissionController.submitTrade")) return point.proceed();
        String call = UUID.randomUUID().toString();
        ArrayDeque<String> stack = CALLS.get();
        String parent = stack.peek();
        int depth = stack.size();
        long started = System.nanoTime();
        write("start", call, parent, depth, component, message, null, 0, null, null);
        stack.push(call);
        Object result = null;
        Throwable failure = null;
        try {
            result = point.proceed();
            return result;
        } catch (Throwable error) {
            failure = error;
            throw error;
        } finally {
            write("end", call, parent, depth, component, message, result,
                    Math.max(0, System.nanoTime() - started) / 1e6, failure, null);
            stack.pop();
            if (stack.isEmpty()) CALLS.remove();
        }
    }

    private void write(String event, String call, String parent, int depth, String component,
            QueueMessage message, Object result, double duration, Throwable error, Instant capturedStart) {
        try {
            var row = new LinkedHashMap<String, Object>();
            row.put("schema_version", 1);
            row.put("event_id", UUID.randomUUID().toString());
            row.put("boot_id", BOOT);
            row.put("sequence", SEQUENCE.incrementAndGet());
            row.put("at", Instant.now().toString());
            row.put("started_at", capturedStart == null ? null : capturedStart.toString());
            row.put("source", "component_log");
            row.put("event", event);
            row.put("call_id", call);
            row.put("parent_call_id", parent);
            row.put("depth", depth);
            row.put("component", component);
            row.put("instance", instance);
            row.put("thread", Thread.currentThread().getName());
            QueueMessage correlation = message != null ? message : result instanceof QueueMessage q ? q : null;
            if (correlation != null) {
                row.put("operation_id", correlation.getOperationId());
                row.put("business_id", correlation.getBusinessId());
                row.put("queue_message_id", correlation.getId());
                row.put("stage", correlation.getQueueName().name());
                row.put("claimed_at", correlation.getClaimedAt() == null ? null : correlation.getClaimedAt().toString());
            }
            if (result instanceof QueueMessage published) row.put("published_message_id", published.getId());
            if (result instanceof org.springframework.http.ResponseEntity<?> response
                    && response.getBody() instanceof java.util.Map<?, ?> body) {
                row.put("operation_id", body.get("operationId"));
                row.put("http_status", response.getStatusCode().value());
            }
            row.put("duration_ms", duration);
            row.put("outcome", error == null ? event.equals("end") ? "returned" : "started" : "exception");
            row.put("error_type", error == null ? null : error.getClass().getName());
            Throwable cause = error;
            for (int i=0; cause != null && cause.getCause()!=null && cause.getCause()!=cause && i<8; i++) cause=cause.getCause();
            row.put("cause_type", cause == null ? null : cause.getClass().getName());
            // No payloads, arguments, SQL, exception messages or credentials in this evidence stream.
            LOG.info(JSON.writeValueAsString(row));
        } catch (Exception loggingFailure) {
            LoggerFactory.getLogger(getClass()).warn("Component journal serialization failed", loggingFailure);
        }
    }
}

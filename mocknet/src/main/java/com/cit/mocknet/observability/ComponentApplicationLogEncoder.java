package com.cit.mocknet.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.encoder.EncoderBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;

/** Agent-free application logs for C. Raw payloads and exception messages stay out. */
public class ComponentApplicationLogEncoder extends EncoderBase<ILoggingEvent> {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> FIELDS = Set.of("operation_id", "business_id", "message_id",
            "queue_message_id", "duration_seconds", "attempt_id", "stage", "outcome", "reason", "worker", "attempt");

    @Override public byte[] headerBytes() { return new byte[0]; }
    @Override public byte[] footerBytes() { return new byte[0]; }

    @Override public byte[] encode(ILoggingEvent event) {
        try {
            var row = new LinkedHashMap<String, Object>();
            row.put("schema_version", 1);
            row.put("event_id", UUID.randomUUID().toString());
            row.put("source", "application_log");
            row.put("at", Instant.ofEpochMilli(event.getTimeStamp()).toString());
            row.put("level", event.getLevel().toString());
            row.put("logger", event.getLoggerName());
            row.put("thread", event.getThreadName());
            // This logger has a fixed message and allowlisted structured outcome fields.
            if (event.getLoggerName().equals(OperationalTelemetry.class.getName())) {
                row.put("message", "Queue attempt outcome");
                if (event.getKeyValuePairs() != null) for (var field : event.getKeyValuePairs()) {
                    if (FIELDS.contains(field.key)) row.put(field.key, field.value);
                }
            } else {
                row.put("message", "Application diagnostic; inspect logger and error class");
            }
            if (event.getThrowableProxy() != null) row.put("error_type", event.getThrowableProxy().getClassName());
            return (JSON.writeValueAsString(row) + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (Exception error) {
            addError("Could not encode C application diagnostic", error);
            return new byte[0];
        }
    }
}

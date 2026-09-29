package com.cit.mocknet.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.event.KeyValuePair;
import static org.junit.jupiter.api.Assertions.*;

class ComponentApplicationLogEncoderTest {
    @Test void preservesCorrelationWithoutPayload() throws Exception {
        var event = new LoggingEvent();
        event.setLoggerName(OperationalTelemetry.class.getName());
        event.setLevel(Level.INFO);
        event.setMessage("sensitive raw payload");
        event.setTimeStamp(1789500000123L);
        event.addKeyValuePair(new KeyValuePair("operation_id", "op-1"));
        event.addKeyValuePair(new KeyValuePair("queue_message_id", 42));
        event.addKeyValuePair(new KeyValuePair("payload", "must disappear"));
        var row = new ObjectMapper().readTree(new ComponentApplicationLogEncoder().encode(event));
        assertEquals("op-1", row.get("operation_id").asText());
        assertEquals(42, row.get("queue_message_id").asInt());
        assertFalse(row.has("payload"));
        assertEquals("Queue attempt outcome", row.get("message").asText());
        assertEquals("2026-09-15T19:20:00.123Z", row.get("at").asText());
    }
}

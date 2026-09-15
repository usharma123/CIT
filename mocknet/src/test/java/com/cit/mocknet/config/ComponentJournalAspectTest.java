package com.cit.mocknet.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cit.mocknet.ingestion.TradeIngestionService;
import com.cit.mocknet.model.QueueMessage;
import com.cit.mocknet.model.QueueName;
import com.cit.mocknet.observability.ComponentJournalAspect;
import com.cit.mocknet.observability.ProcessingContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ComponentJournalAspectTest {
    @Test
    void logsClaimFailuresWithQueueAndRootCauseButSkipsEmptyPolling() throws Throwable {
        Logger logger = (Logger) LoggerFactory.getLogger("mocknet.component.journal");
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start(); logger.addAppender(captured);
        try {
            ComponentJournalAspect aspect = new ComponentJournalAspect();
            ProceedingJoinPoint point = point("claimNext");
            when(point.getArgs()).thenReturn(new Object[]{QueueName.INGESTION,"worker-1"});
            when(point.proceed()).thenReturn(java.util.Optional.empty());
            aspect.recordClaimFailure(point);
            assertTrue(captured.list.isEmpty());
            var failure = new IllegalStateException("private detail", new java.sql.SQLTransientConnectionException("private database detail"));
            when(point.proceed()).thenThrow(failure);
            assertSame(failure, assertThrows(IllegalStateException.class, () -> aspect.recordClaimFailure(point)));
            var row = new ObjectMapper().readTree(captured.list.get(0).getFormattedMessage());
            assertEquals("failure", row.get("event").asText());
            assertEquals("INGESTION", row.get("stage").asText());
            assertEquals("java.sql.SQLTransientConnectionException", row.get("cause_type").asText());
            assertFalse(row.get("started_at").isNull());
            assertFalse(captured.list.get(0).getFormattedMessage().contains("private"));
        } finally { logger.detachAppender(captured); captured.stop(); }
    }

    @Test
    void pairsNestedCallsPreservesFailureAndClearsThreadStateWithoutLoggingPayloads() throws Throwable {
        Logger logger = (Logger) LoggerFactory.getLogger("mocknet.component.journal");
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start(); logger.addAppender(captured);
        QueueMessage message = new QueueMessage();
        message.setId(42L); message.setQueueName(QueueName.INGESTION);
        message.setOperationId("test-operation"); message.setPayload("secret-payload-must-not-be-logged");
        ProcessingContext.set(message);
        try {
            ComponentJournalAspect aspect = new ComponentJournalAspect();
            ProceedingJoinPoint outer = point("processTradeXml");
            ProceedingJoinPoint inner = point("validate");
            IllegalStateException failure = new IllegalStateException("sensitive-exception-message");
            when(inner.proceed()).thenThrow(failure);
            when(outer.proceed()).thenAnswer(invocation -> aspect.record(inner));
            assertSame(failure, assertThrows(IllegalStateException.class, () -> aspect.record(outer)));
            ProceedingJoinPoint next = point("nextCall");
            when(next.proceed()).thenReturn("ok");
            assertEquals("ok", aspect.record(next));
            assertEquals(6, captured.list.size());
            ObjectMapper json = new ObjectMapper();
            var first = json.readTree(captured.list.get(0).getFormattedMessage());
            var child = json.readTree(captured.list.get(1).getFormattedMessage());
            var end = json.readTree(captured.list.get(3).getFormattedMessage());
            var subsequent = json.readTree(captured.list.get(4).getFormattedMessage());
            assertEquals(first.get("call_id"), child.get("parent_call_id"));
            assertEquals(first.get("call_id"), end.get("call_id"));
            assertEquals("exception", end.get("outcome").asText());
            assertEquals(0, subsequent.get("depth").asInt());
            assertTrue(subsequent.get("parent_call_id").isNull());
            for (var event : captured.list) {
                assertFalse(event.getFormattedMessage().contains("secret-payload"));
                assertFalse(event.getFormattedMessage().contains("sensitive-exception"));
            }
        } finally {
            ProcessingContext.clear(); logger.detachAppender(captured); captured.stop();
        }
    }

    private ProceedingJoinPoint point(String name) {
        ProceedingJoinPoint point = mock(ProceedingJoinPoint.class);
        Signature signature = mock(Signature.class);
        when(point.getSignature()).thenReturn(signature);
        when(signature.getDeclaringType()).thenReturn(TradeIngestionService.class);
        when(signature.getName()).thenReturn(name);
        return point;
    }
}

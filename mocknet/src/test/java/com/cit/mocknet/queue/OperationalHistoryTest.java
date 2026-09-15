package com.cit.mocknet.queue;

import com.cit.mocknet.model.*;
import com.cit.mocknet.shared.failure.*;
import com.cit.mocknet.observability.OperationalTelemetry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:operational-history;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop", "mocknet.broker.retry-delay-millis=0"
})
class OperationalHistoryTest {
    @Autowired QueueBroker broker;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    @Test void rollbackDoesNotPublishSuccessfulHistory() {
        QueueMessage published=broker.publish(QueueName.DEAD_LETTER,"{}");
        QueueMessage claim=broker.claimNext(QueueName.DEAD_LETTER,"history-rollback").orElseThrow();
        tx.executeWithoutResult(status -> { broker.complete(claim); status.setRollbackOnly(); });
        assertThat(jdbc.queryForObject("select status from queue_messages where id=?",String.class,published.getId())).isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject("select outcome from processing_attempts where queue_message_id=?",String.class,published.getId())).isEqualTo("processing");
        broker.complete(claim);
        assertThat(jdbc.queryForObject("select outcome from processing_attempts where queue_message_id=?",String.class,published.getId())).isEqualTo("completed");
    }

    @Test void successfulThirdAttemptRetainsBothRetryReasons() {
        QueueMessage published=broker.publish(QueueName.DEAD_LETTER,"{}");
        for(int i=0;i<2;i++) {
            QueueMessage claim=broker.claimNext(QueueName.DEAD_LETTER,"history-retry").orElseThrow();
            assertThat(broker.fail(claim,FailureContext.of("temporary",FailureReason.CONCURRENCY_CONFLICT,true))).isEqualTo(QueueFailureDisposition.RETRIED);
        }
        QueueMessage claim=broker.claimNext(QueueName.DEAD_LETTER,"history-retry").orElseThrow();
        broker.complete(claim);
        assertThat(jdbc.queryForList("select outcome from processing_attempts where queue_message_id=? order by id",String.class,published.getId())).containsExactly("retried","retried","completed");
        assertThat(jdbc.queryForObject("select count(*) from processing_attempts where queue_message_id=? and reason=?",Integer.class,published.getId(),FailureReason.CONCURRENCY_CONFLICT.code())).isEqualTo(2);
        assertThat(jdbc.queryForObject("select status from queue_messages where id=?",String.class,published.getId())).isEqualTo("DONE");
    }

    @Test void obsoleteWorkerCannotOverwriteReclaimedAttempt() {
        QueueMessage published=broker.publish(QueueName.DEAD_LETTER,"{}");
        QueueMessage old=broker.claimNext(QueueName.DEAD_LETTER,"old-worker").orElseThrow();
        jdbc.update("update queue_messages set claimed_at=? where id=?",Timestamp.from(Instant.now().minusSeconds(60)),published.getId());
        QueueMessage replacement=broker.claimNext(QueueName.DEAD_LETTER,"replacement").orElseThrow();
        broker.complete(old);
        assertThat(jdbc.queryForObject("select status from queue_messages where id=?",String.class,published.getId())).isEqualTo("PROCESSING");
        broker.complete(replacement);
        assertThat(jdbc.queryForList("select outcome from processing_attempts where queue_message_id=? order by id",String.class,published.getId())).containsExactly("abandoned","completed");
    }
}

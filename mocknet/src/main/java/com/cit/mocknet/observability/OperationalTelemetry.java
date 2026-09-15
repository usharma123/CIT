package com.cit.mocknet.observability;

import com.cit.mocknet.config.MocknetProperties;
import com.cit.mocknet.model.QueueMessage;
import com.cit.mocknet.model.QueueName;
import io.micrometer.core.instrument.*;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
@EnableScheduling
public class OperationalTelemetry {
    private static final Logger log = LoggerFactory.getLogger(OperationalTelemetry.class);
    private final JdbcTemplate jdbc;
    private final MeterRegistry meters;
    private final String instance;
    private final String version;
    private volatile Map<String, Double> snapshot = Map.of();
    private volatile double snapshotAt;
    private final Map<QueueName, Long> pausedUntil = new ConcurrentHashMap<>();
    private final Map<QueueName, AtomicLong> heartbeat = new ConcurrentHashMap<>();

    public OperationalTelemetry(JdbcTemplate jdbc, MeterRegistry meters, MocknetProperties props,
            @Value("${mocknet.instance:local-1}") String instance, @Value("${MOCKNET_VERSION:l3-demo-v1}") String version) {
        this.jdbc = jdbc;
        this.meters = meters;
        this.instance = instance;
        this.version = version;
        Gauge.builder("mocknet.snapshot.timestamp", this, t -> t.snapshotAt).register(meters);
        for (QueueName q : QueueName.values()) {
            for (String outcome : List.of("completed", "rejected", "retried", "failed")) {
                io.micrometer.core.instrument.Timer.builder("mocknet.processing").tags("stage", q.name(), "outcome", outcome)
                    .publishPercentileHistogram().serviceLevelObjectives(Duration.ofMillis(100), Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(5)).register(meters);
            }
            heartbeat.put(q, new AtomicLong());
            Gauge.builder("mocknet.worker.heartbeat.timestamp", heartbeat.get(q), AtomicLong::doubleValue).tag("stage", q.name()).register(meters);
            for (String state : List.of("ready", "scheduled", "processing", "done", "failed")) {
                String key = q + ":" + state;
                Gauge.builder("mocknet.queue.messages", this, t -> t.snapshot.getOrDefault(key, 0d))
                    .tags("stage", q.name(), "state", state).register(meters);
            }
            for (String state : List.of("ready", "processing")) {
                String key = q + ":age:" + state;
                Gauge.builder("mocknet.queue.oldest.seconds", this, t -> t.snapshot.getOrDefault(key, 0d))
                    .tags("stage", q.name(), "state", state).register(meters);
            }
            Gauge.builder("mocknet.worker.paused", this, t -> t.isPaused(q) ? 1 : 0).tag("stage", q.name()).register(meters);
        }
        int[] sizes = {props.getThreads().getIngestion(), props.getThreads().getMatching(), props.getThreads().getNetting(), props.getThreads().getSettlement()};
        String[] stages = {"INGESTION", "MATCHING", "NETTING", "SETTLEMENT"};
        for (int i=0; i<sizes.length; i++) Gauge.builder("mocknet.worker.configured", sizes[i], Number::doubleValue).tag("stage", stages[i]).strongReference(true).register(meters);
    }

    public boolean isPaused(QueueName stage) { return pausedUntil.getOrDefault(stage, 0L) > System.currentTimeMillis(); }
    public void pause(QueueName stage, int seconds) { pausedUntil.put(stage, System.currentTimeMillis() + seconds * 1000L); }
    public void heartbeat(QueueName stage) { heartbeat.get(stage).set(Instant.now().getEpochSecond()); }

    public void claimed(QueueMessage m) {
        Instant now = m.getClaimedAt();
        // A crashed worker's previous claim remains visible as abandoned, with its original timing.
        // No completion was observed. Leave duration unknown rather than reporting
        // a zero-second handler or treating the reclaim interval as execution time.
        jdbc.update("update processing_attempts set outcome='abandoned', finished_at=? where queue_message_id=? and outcome='processing'", Timestamp.from(now), m.getId());
        jdbc.update("insert into processing_attempts (queue_message_id,operation_id,queue_name,claimed_at,attempt_number,wait_seconds,processing_seconds,outcome,worker,instance,service_version) values (?,?,?,?,?,?,?,?,?,?,?)",
            m.getId(), m.getOperationId(), m.getQueueName().name(), Timestamp.from(now), m.getAttempts()+1,
            Math.max(0, Duration.between(m.getAvailableAt(), now).toNanos()/1e9), 0d, "processing", m.getWorkerName(), instance, version);
    }

    public void attachTrace(QueueMessage m, Span span) {
        var sc = span.getSpanContext();
        jdbc.update("update processing_attempts set trace_id=?,span_id=? where queue_message_id=? and claimed_at=?",
            sc.isValid() ? sc.getTraceId() : null, sc.isValid() ? sc.getSpanId() : null, m.getId(), Timestamp.from(m.getClaimedAt()));
    }

    public void finished(QueueMessage m, String outcome, String reason) {
        Instant now = Instant.now();
        double seconds = Math.max(0, Duration.between(m.getClaimedAt(), now).toNanos()/1e9);
        jdbc.update("update processing_attempts set outcome=?,reason=?,finished_at=?,processing_seconds=? where queue_message_id=? and claimed_at=?",
            outcome, reason, Timestamp.from(now), seconds, m.getId(), Timestamp.from(m.getClaimedAt()));
        jdbc.update("update queue_messages set outcome=? where id=?", outcome, m.getId());
        Runnable publish = () -> {
            io.micrometer.core.instrument.Timer.builder("mocknet.processing").tags("stage", m.getQueueName().name(), "outcome", outcome)
                .publishPercentileHistogram().serviceLevelObjectives(Duration.ofMillis(100), Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(5))
                .register(meters).record(Duration.ofNanos((long)(seconds*1e9)));
            io.micrometer.core.instrument.Timer.builder("mocknet.queue.wait").tag("stage", m.getQueueName().name()).publishPercentileHistogram()
                .register(meters).record(Duration.between(m.getAvailableAt(), m.getClaimedAt()).isNegative() ? Duration.ZERO : Duration.between(m.getAvailableAt(), m.getClaimedAt()));
            log.atInfo().addKeyValue("operation_id", m.getOperationId()).addKeyValue("business_id", m.getBusinessId())
                .addKeyValue("queue_message_id", m.getId()).addKeyValue("stage", m.getQueueName().name())
                .addKeyValue("attempt", m.getAttempts()+1).addKeyValue("outcome", outcome).addKeyValue("reason", reason)
                .addKeyValue("duration_seconds", seconds).log("Queue attempt finished");
        };
        // Metrics and success logs describe committed dispositions only.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { publish.run(); }
            });
        } else publish.run();
    }

    @Scheduled(fixedDelayString = "${mocknet.metrics.snapshot-millis:5000}", initialDelay = 5000)
    public void refresh() {
        try {
            Map<String, Double> next = new HashMap<>();
            Instant now = Instant.now();
            jdbc.query("select queue_name,status,available_at,claimed_at,count(*) as n from queue_messages where status in ('NEW','PROCESSING') group by queue_name,status,available_at,claimed_at", rs -> {
                String stage = rs.getString("queue_name");
                boolean processing = "PROCESSING".equals(rs.getString("status"));
                Instant time = rs.getTimestamp(processing ? "claimed_at" : "available_at").toInstant();
                String state = processing ? "processing" : time.isAfter(now) ? "scheduled" : "ready";
                next.merge(stage+":"+state, rs.getDouble("n"), Double::sum);
                if (!"scheduled".equals(state)) next.merge(stage+":age:"+state, Math.max(0, Duration.between(time,now).toMillis()/1000d), Math::max);
            });
            jdbc.query("select queue_name,status,count(*) as n from queue_messages where status in ('DONE','FAILED') group by queue_name,status", rs -> {
                next.put(rs.getString("queue_name")+":"+rs.getString("status").toLowerCase(Locale.ROOT), rs.getDouble("n"));
            });
            snapshot = Map.copyOf(next);
            snapshotAt = now.getEpochSecond();
        } catch (RuntimeException e) {
            meters.counter("mocknet.snapshot.failures").increment();
            log.warn("Queue metrics snapshot failed; retaining last snapshot timestamp", e);
        }
    }
}

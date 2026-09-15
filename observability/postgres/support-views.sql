-- Deliberately exclude raw XML, payloads, counterparties, and exception messages.
-- Bound time-window diagnostics without blocking writes during index creation.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_queue_support_created ON public.queue_messages (created_at);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_queue_support_completed ON public.queue_messages (completed_at) WHERE completed_at IS NOT NULL;
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_trade_support_operation ON public.trades (operation_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_matched_support_trade1 ON public.matched_trades (trade1id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_matched_support_trade2 ON public.matched_trades (trade2id);
CREATE SCHEMA IF NOT EXISTS support;
CREATE OR REPLACE VIEW support.queue_messages AS
SELECT id, operation_id, business_id, queue_name AS stage, status, outcome, attempts AS failed_attempts,
       created_at, available_at, claimed_at, completed_at, worker_name,
       substring(trace_context from 4 for 32) AS trace_id
FROM public.queue_messages;
-- Read-only current backlog. No dashboard time filter: old stuck work must stay visible.
CREATE OR REPLACE VIEW support.queue_health AS
WITH queues(stage, path) AS (VALUES
 ('INGESTION','Active'),('MATCHING','Active'),('NETTING','Active'),
 ('SETTLEMENT','Unused by normal flow'),('DEAD_LETTER','Parked; no consumer'))
SELECT queues.stage, queues.path, now() AS observed_at,
 count(q.id) FILTER (WHERE q.status='NEW' AND q.available_at<=now() AND queues.stage<>'DEAD_LETTER') AS ready,
 count(q.id) FILTER (WHERE q.status='NEW' AND q.available_at>now() AND queues.stage<>'DEAD_LETTER') AS scheduled,
 count(q.id) FILTER (WHERE q.status='PROCESSING') AS processing,
 count(q.id) FILTER (WHERE queues.stage='DEAD_LETTER') AS dead_letters,
 extract(epoch FROM now()-min(q.available_at) FILTER (WHERE q.status='NEW' AND q.available_at<=now() AND queues.stage<>'DEAD_LETTER')) AS oldest_ready_s,
 extract(epoch FROM now()-min(q.claimed_at) FILTER (WHERE q.status='PROCESSING')) AS oldest_processing_s
FROM queues LEFT JOIN public.queue_messages q ON q.queue_name=queues.stage AND q.status IN ('NEW','PROCESSING')
GROUP BY queues.stage,queues.path;
CREATE OR REPLACE VIEW support.attempts AS
SELECT a.id, a.queue_message_id, a.operation_id, q.business_id, a.queue_name AS stage,
       a.claimed_at, a.finished_at, a.attempt_number, a.wait_seconds, a.processing_seconds,
       a.outcome, a.reason, a.worker, a.instance, a.trace_id, a.span_id, a.service_version
FROM public.processing_attempts a JOIN public.queue_messages q ON q.id=a.queue_message_id;
CREATE OR REPLACE VIEW support.operation_links AS
SELECT t1.operation_id, t2.operation_id AS related_operation_id, m.id AS matched_trade_id
FROM public.matched_trades m JOIN public.trades t1 ON t1.id=m.trade1id JOIN public.trades t2 ON t2.id=m.trade2id
WHERE t1.operation_id IS NOT NULL AND t2.operation_id IS NOT NULL
UNION
SELECT t2.operation_id, t1.operation_id, m.id
FROM public.matched_trades m JOIN public.trades t1 ON t1.id=m.trade1id JOIN public.trades t2 ON t2.id=m.trade2id
WHERE t1.operation_id IS NOT NULL AND t2.operation_id IS NOT NULL;
CREATE OR REPLACE VIEW support.operations AS
SELECT q.operation_id, q.business_id, q.id AS ingestion_message_id, q.created_at AS accepted_at,
       t.id AS trade_record_id, t.status AS trade_status,
       CASE WHEN summary.failed>0 THEN 'FAILED'
            WHEN t.status='REJECTED' THEN 'REJECTED'
            WHEN t.status='NETTED' THEN 'COMPLETED'
            WHEN summary.processing>0 THEN 'PROCESSING'
            WHEN summary.waiting>0 THEN 'QUEUED'
            WHEN t.status='VALIDATED' THEN 'AWAITING_COUNTERPARTY'
            ELSE 'PENDING' END AS outcome,
       summary.retried>0 AND summary.failed=0 AND summary.waiting=0 AND summary.processing=0 AS recovered,
       summary.last_progress_at, summary.failed, summary.waiting, summary.processing,
       extract(epoch FROM COALESCE(summary.last_progress_at,q.created_at)-q.created_at) AS progress_elapsed_seconds,
       CASE WHEN t.status IN ('NETTED','REJECTED') OR summary.failed>0 THEN extract(epoch FROM summary.last_progress_at-q.created_at) END AS terminal_latency_seconds,
       substring(q.trace_context from 4 for 32) AS trace_id
FROM public.queue_messages q
LEFT JOIN public.trades t ON t.operation_id=q.operation_id
LEFT JOIN LATERAL (
  SELECT count(*) FILTER(WHERE m.status='FAILED') AS failed,
         count(*) FILTER(WHERE m.status='NEW') AS waiting,
         count(*) FILTER(WHERE m.status='PROCESSING') AS processing,
         count(*) FILTER(WHERE m.attempts>0) AS retried,
         max(COALESCE(m.completed_at,m.claimed_at,m.created_at)) AS last_progress_at
  FROM public.queue_messages m
  WHERE m.queue_name<>'DEAD_LETTER' AND
        m.operation_id=ANY(ARRAY[q.operation_id] || ARRAY(SELECT related_operation_id FROM support.operation_links WHERE operation_id=q.operation_id))
) summary ON true
WHERE q.queue_name='INGESTION';
CREATE OR REPLACE VIEW support.database_activity AS
SELECT state, wait_event_type, wait_event, count(*) AS connections
FROM pg_stat_activity WHERE datname=current_database() GROUP BY state,wait_event_type,wait_event;

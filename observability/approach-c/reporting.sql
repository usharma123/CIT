CREATE TABLE IF NOT EXISTS evidence (
  event_id text PRIMARY KEY, source text NOT NULL, at timestamptz NOT NULL,
  observed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  sequence bigint, kind text NOT NULL, entity_id text, operation_id text, body jsonb NOT NULL
);
CREATE INDEX IF NOT EXISTS evidence_operation ON evidence(operation_id,at);
CREATE INDEX IF NOT EXISTS evidence_latest ON evidence(source,kind,entity_id,sequence DESC);
CREATE INDEX IF NOT EXISTS evidence_time ON evidence(at);
CREATE TABLE IF NOT EXISTS log_checkpoints (file_id text PRIMARY KEY, position bigint NOT NULL, path text NOT NULL);
CREATE TABLE IF NOT EXISTS quarantine (file_id text, position bigint, error text, at timestamptz DEFAULT now(), PRIMARY KEY(file_id,position));
CREATE TABLE IF NOT EXISTS collector_status (id integer PRIMARY KEY CHECK(id=1), at timestamptz NOT NULL, pending_journal bigint, oldest_pending_seconds double precision, note text);
CREATE TABLE IF NOT EXISTS queue_samples (at timestamptz NOT NULL, stage text NOT NULL, ready bigint, scheduled bigint, processing bigint, oldest_ready_seconds double precision, oldest_processing_seconds double precision, PRIMARY KEY(at,stage));

CREATE OR REPLACE VIEW c_queue AS
SELECT body->>'id' AS message_id, operation_id, body->>'business_id' AS business_id,
  body->>'queue_name' AS stage, body->>'status' AS status, body->>'outcome' AS outcome,
  (body->>'attempts')::int AS retries, (body->>'created_at')::timestamptz AS created_at,
  (body->>'available_at')::timestamptz AS available_at, (body->>'claimed_at')::timestamptz AS claimed_at,
  body->>'worker_name' AS worker, at AS last_event_at,
  CASE WHEN body->>'status'='NEW' AND (body->>'available_at')::timestamptz > now() THEN 'scheduled'
       WHEN body->>'status'='NEW' THEN 'ready' ELSE lower(body->>'status') END AS state
FROM (SELECT DISTINCT ON(entity_id) * FROM evidence WHERE source='mq_journal' AND kind='queue_messages'
      ORDER BY entity_id, sequence DESC) e;

CREATE OR REPLACE VIEW c_attempt AS
SELECT entity_id AS attempt_id, operation_id, body->>'queue_message_id' AS message_id,
 body->>'queue_name' AS stage, (body->>'attempt_number')::int AS attempt,
 (body->>'claimed_at')::timestamptz AS started_at, (body->>'finished_at')::timestamptz AS finished_at,
 (body->>'wait_seconds')::double precision AS wait_seconds,
 (body->>'processing_seconds')::double precision AS duration_seconds,
 body->>'outcome' AS outcome, body->>'reason' AS reason, body->>'worker' AS worker
FROM (SELECT DISTINCT ON(entity_id) * FROM evidence WHERE source='mq_journal' AND kind='processing_attempts'
      ORDER BY entity_id,sequence DESC) e;

CREATE OR REPLACE VIEW c_call AS
WITH starts AS (SELECT * FROM evidence WHERE source='component_log' AND kind='start'),
ends AS (SELECT * FROM evidence WHERE source='component_log' AND kind IN('end','failure'))
SELECT coalesce(s.entity_id,e.entity_id) AS call_id, coalesce(e.operation_id,s.operation_id) AS operation_id,
 coalesce(s.body->>'parent_call_id',e.body->>'parent_call_id') AS parent_call_id,
 coalesce(s.body->>'component',e.body->>'component') AS component,
 coalesce(s.body->>'queue_message_id',e.body->>'queue_message_id') AS message_id,
 coalesce(s.body->>'stage',e.body->>'stage','ADMISSION') AS stage,
 coalesce(s.body->>'thread',e.body->>'thread') AS worker,
 coalesce(s.body->>'depth',e.body->>'depth')::int AS depth,
 coalesce(s.at,(e.body->>'started_at')::timestamptz) AS started_at, e.at AS finished_at, (e.body->>'duration_ms')::double precision AS duration_ms,
 e.body->>'outcome' AS outcome, e.body->>'error_type' AS error_type,
 e.body->>'published_message_id' AS published_message_id,
 CASE WHEN e.kind='failure' THEN 'exception record' WHEN s.event_id IS NULL THEN 'missing start' WHEN e.event_id IS NULL THEN 'open or missing end' ELSE 'paired' END AS evidence_state
FROM starts s FULL JOIN ends e ON s.entity_id=e.entity_id;

CREATE OR REPLACE VIEW c_operations AS
SELECT q.operation_id, max(q.business_id) AS business_id, min(q.created_at) AS admitted_at,
 max(q.last_event_at) AS last_event_at, count(*) AS messages,
 count(*) FILTER(WHERE q.stage!='DEAD_LETTER' AND q.state IN('ready','scheduled','processing')) AS unfinished,
 count(*) FILTER(WHERE q.status='FAILED') AS failed_messages,
 count(*) FILTER(WHERE q.stage='DEAD_LETTER') AS dead_letters,
 CASE WHEN bool_or(q.status='FAILED') THEN 'failed'
      WHEN bool_or(q.state IN('ready','scheduled','processing') AND q.stage!='DEAD_LETTER') THEN 'in flight'
      WHEN bool_or(q.outcome='rejected') THEN 'rejected' ELSE 'queue work finished' END AS disposition
FROM c_queue q GROUP BY q.operation_id;

CREATE OR REPLACE VIEW c_evidence_health AS
SELECT at AS collected_at, extract(epoch FROM now()-at)::double precision AS collector_age_seconds,
 pending_journal,oldest_pending_seconds,note,
 (SELECT count(*) FROM quarantine) AS quarantined_lines,
 (SELECT count(*) FROM c_call WHERE evidence_state IN('missing start','open or missing end') AND coalesce(started_at,finished_at)<now()-interval '30 seconds') AS incomplete_calls,
 (SELECT coalesce(sum(gaps),0) FROM (SELECT max(sequence)-min(sequence)+1-count(DISTINCT sequence) AS gaps FROM evidence WHERE source='component_log' GROUP BY body->>'boot_id') g) AS internal_log_sequence_gaps
FROM collector_status WHERE id=1;

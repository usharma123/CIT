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

-- Keep the latest committed journal row once instead of sorting all history
-- independently for every dashboard panel. The full evidence remains available.
CREATE TABLE IF NOT EXISTS c_journal_latest (
 kind text NOT NULL, entity_id text NOT NULL, event_id text NOT NULL,
 operation_id text, at timestamptz NOT NULL, sequence bigint NOT NULL, body jsonb NOT NULL,
 PRIMARY KEY(kind,entity_id)
);
CREATE INDEX IF NOT EXISTS c_journal_latest_operation ON c_journal_latest(operation_id,kind);
CREATE INDEX IF NOT EXISTS c_journal_latest_related ON c_journal_latest((body->>'related_operation_id')) WHERE kind='matched_trades';
CREATE OR REPLACE FUNCTION c_update_journal_latest() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.source='mq_journal' AND NEW.entity_id IS NOT NULL AND NEW.sequence IS NOT NULL THEN
  INSERT INTO c_journal_latest(kind,entity_id,event_id,operation_id,at,sequence,body)
  VALUES(NEW.kind,NEW.entity_id,NEW.event_id,NEW.operation_id,NEW.at,NEW.sequence,NEW.body)
  ON CONFLICT(kind,entity_id) DO UPDATE SET event_id=excluded.event_id,operation_id=excluded.operation_id,
   at=excluded.at,sequence=excluded.sequence,body=excluded.body
  WHERE c_journal_latest.sequence<excluded.sequence;
 END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS c_update_journal_latest ON evidence;
CREATE TRIGGER c_update_journal_latest AFTER INSERT ON evidence FOR EACH ROW EXECUTE FUNCTION c_update_journal_latest();
INSERT INTO c_journal_latest(kind,entity_id,event_id,operation_id,at,sequence,body)
 SELECT DISTINCT ON(kind,entity_id) kind,entity_id,event_id,operation_id,at,sequence,body FROM evidence
 WHERE source='mq_journal' AND entity_id IS NOT NULL AND sequence IS NOT NULL
 ORDER BY kind,entity_id,sequence DESC
 ON CONFLICT(kind,entity_id) DO UPDATE SET event_id=excluded.event_id,operation_id=excluded.operation_id,
 at=excluded.at,sequence=excluded.sequence,body=excluded.body WHERE c_journal_latest.sequence<excluded.sequence;

CREATE OR REPLACE VIEW c_queue AS
SELECT body->>'id' AS message_id, operation_id, body->>'business_id' AS business_id,
  body->>'queue_name' AS stage, body->>'status' AS status, body->>'outcome' AS outcome,
  (body->>'attempts')::int AS retries, (body->>'created_at')::timestamptz AS created_at,
  (body->>'available_at')::timestamptz AS available_at, (body->>'claimed_at')::timestamptz AS claimed_at,
  body->>'worker_name' AS worker, at AS last_event_at,
  CASE WHEN body->>'status'='NEW' AND (body->>'available_at')::timestamptz > now() THEN 'scheduled'
       WHEN body->>'status'='NEW' THEN 'ready' ELSE lower(body->>'status') END AS state
FROM c_journal_latest e WHERE kind='queue_messages';

CREATE OR REPLACE VIEW c_attempt AS
SELECT entity_id AS attempt_id, operation_id, body->>'queue_message_id' AS message_id,
 body->>'queue_name' AS stage, (body->>'attempt_number')::int AS attempt,
 (body->>'claimed_at')::timestamptz AS started_at, (body->>'finished_at')::timestamptz AS finished_at,
 (body->>'wait_seconds')::double precision AS wait_seconds,
 CASE WHEN body->>'outcome' IN ('processing','abandoned') THEN NULL
      ELSE (body->>'processing_seconds')::double precision END AS duration_seconds,
 body->>'outcome' AS outcome, body->>'reason' AS reason, body->>'worker' AS worker
FROM c_journal_latest e WHERE kind='processing_attempts';

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

CREATE OR REPLACE VIEW c_trade AS
 SELECT operation_id,body->>'status' AS status,at AS recorded_at FROM c_journal_latest WHERE kind='trades';
CREATE OR REPLACE VIEW c_operation_links AS
 WITH latest AS NOT MATERIALIZED (SELECT operation_id,body->>'related_operation_id' AS related_operation_id,entity_id AS matched_trade_id
 FROM c_journal_latest WHERE kind='matched_trades')
 SELECT * FROM latest UNION SELECT related_operation_id,operation_id,matched_trade_id FROM latest;
CREATE OR REPLACE VIEW c_business_operations AS
 WITH queues AS MATERIALIZED (SELECT * FROM c_queue),
 mapping AS (SELECT DISTINCT operation_id,operation_id AS source_operation FROM queues
 UNION SELECT operation_id,related_operation_id FROM c_operation_links),
 summary AS (
 SELECT m.operation_id,count(*) FILTER(WHERE q.status='FAILED') AS failed,
 count(*) FILTER(WHERE q.status='NEW') AS waiting,count(*) FILTER(WHERE q.status='PROCESSING') AS processing,
 count(*) FILTER(WHERE q.retries>0) AS retried,max(q.last_event_at) AS last_progress_at
 FROM mapping m JOIN queues q ON q.operation_id=m.source_operation AND q.stage<>'DEAD_LETTER' GROUP BY m.operation_id
 )
 SELECT o.*,t.status AS trade_status,
 CASE WHEN s.failed>0 THEN 'FAILED' WHEN t.status='REJECTED' THEN 'REJECTED' WHEN t.status='NETTED' THEN 'COMPLETED'
 WHEN s.processing>0 THEN 'PROCESSING' WHEN s.waiting>0 THEN 'QUEUED' WHEN t.status='VALIDATED' THEN 'AWAITING_COUNTERPARTY'
 ELSE 'PENDING' END AS business_outcome,
 s.retried>0 AND s.failed=0 AND s.waiting=0 AND s.processing=0 AS recovered,
 CASE WHEN t.status IN('NETTED','REJECTED') OR s.failed>0 THEN extract(epoch FROM s.last_progress_at-o.admitted_at)::double precision END AS terminal_latency_seconds
 FROM c_operations o LEFT JOIN c_trade t USING(operation_id) LEFT JOIN summary s USING(operation_id);
GRANT SELECT ON c_trade,c_operation_links,c_business_operations TO grafana_c;

CREATE OR REPLACE FUNCTION c_calls_for(selected_operation text) RETURNS SETOF c_call LANGUAGE sql STABLE AS $calls$
WITH ids AS MATERIALIZED (SELECT DISTINCT entity_id FROM evidence WHERE source='component_log' AND operation_id=selected_operation), starts AS (SELECT * FROM evidence WHERE source='component_log' AND kind='start' AND entity_id IN (SELECT entity_id FROM ids)),
ends AS (SELECT * FROM evidence WHERE source='component_log' AND kind IN('end','failure') AND entity_id IN (SELECT entity_id FROM ids))
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
FROM starts s FULL JOIN ends e ON s.entity_id=e.entity_id
$calls$;
REVOKE ALL ON FUNCTION c_calls_for(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION c_calls_for(text) TO grafana_c;

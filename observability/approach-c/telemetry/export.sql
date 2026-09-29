-- Durable delivery records belong to the reporting store, never the application DB.
CREATE TABLE IF NOT EXISTS c_telemetry_settings (
 id integer PRIMARY KEY CHECK(id=1), enabled_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO c_telemetry_settings(id) VALUES(1) ON CONFLICT DO NOTHING;
CREATE TABLE IF NOT EXISTS c_trace_activity (
 operation_id text PRIMARY KEY, changed_at timestamptz NOT NULL, version bigint NOT NULL DEFAULT 1
);
ALTER TABLE c_trace_activity ADD COLUMN IF NOT EXISTS checked_at timestamptz;
CREATE TABLE IF NOT EXISTS c_trace_exports (
 operation_id text PRIMARY KEY, trace_id text UNIQUE NOT NULL, source_version bigint NOT NULL,
 state text NOT NULL CHECK(state IN ('pending','sending','exported','uncertain','rejected')),
 payload jsonb, span_ids jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 sent_at timestamptz, error_code text
);
CREATE TABLE IF NOT EXISTS c_log_exports (
 event_id text PRIMARY KEY, state text NOT NULL DEFAULT 'pending'
 CHECK(state IN ('pending','sending','exported','uncertain','rejected')),
 batch_id text, sent_at timestamptz, error_code text
);
CREATE INDEX IF NOT EXISTS c_log_export_pending ON c_log_exports(state,event_id);
-- Queue ordering must not scan all previously delivered evidence for every batch.
-- Older terminal delivery records may no longer have retained source evidence.
ALTER TABLE c_log_exports ADD COLUMN IF NOT EXISTS event_at timestamptz;
UPDATE c_log_exports x SET event_at=e.at FROM evidence e
 WHERE x.event_id=e.event_id AND x.state='pending' AND x.event_at IS NULL;
CREATE INDEX IF NOT EXISTS c_log_export_pending_time
 ON c_log_exports(event_at,event_id) WHERE state='pending';
CREATE OR REPLACE FUNCTION c_telemetry_enqueue() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.body->>'baseline'='true' THEN RETURN NEW; END IF;
 INSERT INTO c_log_exports(event_id,event_at) VALUES(NEW.event_id,NEW.at) ON CONFLICT DO NOTHING;
 IF NEW.operation_id IS NOT NULL THEN
  INSERT INTO c_trace_activity(operation_id,changed_at) VALUES(NEW.operation_id,clock_timestamp())
  ON CONFLICT(operation_id) DO UPDATE SET changed_at=excluded.changed_at,version=c_trace_activity.version+1;
 END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS c_telemetry_enqueue ON evidence;
CREATE TRIGGER c_telemetry_enqueue AFTER INSERT ON evidence FOR EACH ROW EXECUTE FUNCTION c_telemetry_enqueue();
-- Backfill only the recent window on first deployment; INSERT conflicts never reset delivery state.
INSERT INTO c_trace_activity(operation_id,changed_at)
 SELECT operation_id,max(observed_at) FROM evidence
 WHERE observed_at >= (SELECT enabled_at-interval '15 minutes' FROM c_telemetry_settings WHERE id=1)
 AND coalesce(body->>'baseline','false')<>'true' AND operation_id IS NOT NULL GROUP BY operation_id ON CONFLICT DO NOTHING;
INSERT INTO c_log_exports(event_id,event_at)
 SELECT event_id,at FROM evidence WHERE observed_at >= (SELECT enabled_at-interval '15 minutes' FROM c_telemetry_settings WHERE id=1)
 AND coalesce(body->>'baseline','false')<>'true' ON CONFLICT DO NOTHING;
CREATE TABLE IF NOT EXISTS c_telemetry_status (
 id integer PRIMARY KEY CHECK(id=1), at timestamptz NOT NULL, error_code text
);
CREATE OR REPLACE VIEW c_telemetry_health AS
 SELECT s.at AS checked_at,extract(epoch FROM now()-s.at)::double precision AS exporter_age_seconds,s.error_code,
 (SELECT count(*) FROM c_log_exports WHERE state='pending') AS pending_logs,
 (SELECT count(*) FROM c_log_exports WHERE state IN('uncertain','rejected')) AS unresolved_logs,
 (SELECT count(*) FROM c_trace_exports WHERE state='exported') AS exported_traces,
 (SELECT count(*) FROM c_trace_exports WHERE state IN('uncertain','rejected')) AS unresolved_traces,
 (SELECT count(*) FROM c_trace_activity a LEFT JOIN c_trace_exports e USING(operation_id) WHERE e.operation_id IS NULL) AS awaiting_traces,
 (SELECT count(*) FROM c_trace_activity a JOIN c_trace_exports e USING(operation_id) WHERE a.version<>e.source_version) AS changed_after_export
 FROM c_telemetry_status s WHERE id=1;
CREATE OR REPLACE VIEW c_operation_trace AS
 SELECT a.operation_id,md5('c-reconstruction:'||a.operation_id) AS trace_id,
 CASE WHEN e.operation_id IS NULL THEN 'awaiting settled evidence'
      WHEN a.version<>e.source_version THEN 'new evidence after snapshot'
      ELSE e.state END AS export_state,e.sent_at,e.error_code
 FROM c_trace_activity a LEFT JOIN c_trace_exports e USING(operation_id);
GRANT SELECT ON c_telemetry_health,c_operation_trace TO grafana_c;

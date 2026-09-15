CREATE TABLE IF NOT EXISTS source_snapshots (
  source_id text PRIMARY KEY, label text NOT NULL, provenance text NOT NULL,
  status text NOT NULL, attempted_at timestamptz NOT NULL,
  sampled_at timestamptz, last_success_at timestamptz,
  rows jsonb NOT NULL DEFAULT '[]', error_code text
);
ALTER TABLE source_snapshots ADD COLUMN IF NOT EXISTS stale_after_seconds integer NOT NULL DEFAULT 45 CHECK(stale_after_seconds>0);
CREATE OR REPLACE VIEW c_source_status AS
SELECT source_id,label,provenance,
 CASE WHEN status='not configured' THEN status
      WHEN now()-attempted_at>make_interval(secs=>stale_after_seconds) THEN 'collector stale'
      WHEN status='ok' AND now()-sampled_at>make_interval(secs=>stale_after_seconds) THEN 'stale'
      ELSE status END AS status,
 attempted_at,sampled_at,last_success_at,
 extract(epoch FROM now()-sampled_at)::double precision AS source_age_seconds,error_code
FROM source_snapshots;
CREATE OR REPLACE VIEW c_source_values AS
SELECT s.source_id,s.label,s.status,s.sampled_at,
 row->>'category' AS category,row->>'item' AS item,row->>'value' AS value
FROM source_snapshots s CROSS JOIN LATERAL jsonb_array_elements(s.rows) row;
CREATE TABLE IF NOT EXISTS tool_runs (
 run_id uuid PRIMARY KEY,tool text NOT NULL,requested_by text NOT NULL,
 requested_at timestamptz NOT NULL DEFAULT clock_timestamp(),finished_at timestamptz,
 operation_id text,status text NOT NULL,result jsonb,error_code text
);
CREATE INDEX IF NOT EXISTS c_tool_runs_time ON tool_runs(requested_at DESC);

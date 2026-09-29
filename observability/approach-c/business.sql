CREATE INDEX IF NOT EXISTS evidence_business_kind ON evidence(kind,operation_id,sequence) WHERE source='mq_journal';
CREATE INDEX IF NOT EXISTS evidence_business_related ON evidence((body->>'related_operation_id'),kind,sequence)
 WHERE source='mq_journal' AND kind IN('netting_sets','settlement_instructions');
CREATE TABLE IF NOT EXISTS c_sla_policy (
 policy_id text PRIMARY KEY, label text NOT NULL, target_seconds double precision NOT NULL CHECK(target_seconds>0),
 warning_fraction double precision NOT NULL CHECK(warning_fraction>0 AND warning_fraction<1),
 description text NOT NULL
);
INSERT INTO c_sla_policy VALUES
 ('validation','Validation',5,0.8,'DEMO target. Receipt to committed validation or rejection; elapsed UTC time.'),
 ('matching','Matching',30,0.8,'DEMO target. Receipt to committed match; includes counterparty wait. Rejections are excluded.'),
 ('instructions','Instruction readiness',60,0.8,'DEMO target. Receipt to netting complete and all required instructions generated. Rejections are excluded; external settlement is outside scope.')
ON CONFLICT DO NOTHING;

CREATE OR REPLACE VIEW c_process_events AS
WITH journal AS NOT MATERIALIZED (
 SELECT e.*,coalesce((body->>'baseline')::boolean,false) AS baseline
 FROM evidence e WHERE source='mq_journal'
), events AS (
 SELECT event_id,operation_id,at,sequence,'Received'::text AS milestone,baseline,kind,entity_id,body
 FROM journal WHERE kind='queue_messages' AND body->>'queue_name'='INGESTION'
 AND body->>'status'='NEW' AND coalesce((body->>'attempts')::int,0)=0
 AND sequence=(SELECT min(x.sequence) FROM evidence x WHERE x.source='mq_journal' AND x.kind='queue_messages' AND x.entity_id=journal.entity_id)
 UNION ALL
 SELECT event_id,operation_id,at,sequence,
 CASE body->>'status' WHEN 'RECEIVED' THEN 'Received' WHEN 'VALIDATED' THEN 'Validated' WHEN 'MATCHED' THEN 'Matched'
 WHEN 'NETTED' THEN 'Netted' WHEN 'REJECTED' THEN 'Rejected' ELSE body->>'status' END,
 baseline,kind,entity_id,body FROM journal WHERE kind='trades'
 UNION ALL
 SELECT event_id,operation_id,at,sequence,
 CASE WHEN kind='netting_sets' THEN CASE WHEN (body->>'instruction_required')::boolean THEN 'Netting output recorded' ELSE 'Instruction not required' END
 ELSE 'Instruction '||lower(body->>'status') END,baseline,kind,entity_id,body
 FROM journal WHERE kind IN('netting_sets','settlement_instructions')
 UNION ALL
 SELECT event_id,body->>'related_operation_id',at,sequence,
 CASE WHEN kind='netting_sets' THEN CASE WHEN (body->>'instruction_required')::boolean THEN 'Netting output recorded' ELSE 'Instruction not required' END
 ELSE 'Instruction '||lower(body->>'status') END,baseline,kind,entity_id,body
 FROM journal WHERE kind IN('netting_sets','settlement_instructions') AND body->>'related_operation_id' IS NOT NULL
 AND body->>'related_operation_id' IS DISTINCT FROM operation_id
 UNION ALL
 SELECT event_id,operation_id,at,sequence,
 CASE body->>'outcome' WHEN 'retried' THEN 'Retry scheduled' WHEN 'failed' THEN 'Processing failed' WHEN 'rejected' THEN 'Rejected' ELSE 'Worker abandoned' END,
 baseline,kind,entity_id,body FROM journal WHERE kind='processing_attempts' AND body->>'outcome' IN('retried','failed','rejected','abandoned')
 UNION ALL
 SELECT j.event_id,l.operation_id,j.at,j.sequence,'Processing failed',j.baseline,j.kind,j.entity_id,j.body
 FROM journal j JOIN c_operation_links l ON l.related_operation_id=j.operation_id
 WHERE j.kind='processing_attempts' AND j.body->>'outcome'='failed' AND j.body->>'queue_name'='NETTING'
 AND l.operation_id IS DISTINCT FROM j.operation_id
)
SELECT * FROM events WHERE operation_id IS NOT NULL;

CREATE OR REPLACE VIEW c_process_summary AS
SELECT o.operation_id,o.business_id,o.admitted_at,m.received_at,m.validated_at,m.matched_at,m.netted_at,m.rejected_at,
 CASE WHEN m.netted_at IS NOT NULL AND x.netting_outputs>0 AND x.required_outputs=x.timed_generated_outputs AND NOT x.has_baseline
 THEN greatest(m.netted_at,x.outputs_at) END AS instructions_at,
 CASE WHEN t.status='REJECTED' THEN 'Rejected' WHEN b.business_outcome='FAILED' THEN 'Failed'
 WHEN t.status='NETTED' AND x.netting_outputs>0 AND x.required_outputs=x.generated_outputs THEN 'Instructions ready'
 WHEN t.status='NETTED' THEN 'Netted; output evidence missing'
 WHEN t.status='MATCHED' THEN 'Matched' WHEN t.status='VALIDATED' THEN 'Waiting for counterparty' ELSE 'Received / processing' END AS business_state,
 coalesce(m.retries,0) AS retries,coalesce(m.has_baseline,false) AS has_baseline,
 coalesce(x.required_outputs,0) AS required_outputs,coalesce(x.generated_outputs,0) AS generated_outputs
FROM c_operations o LEFT JOIN LATERAL (
 SELECT
 min(at) FILTER(WHERE milestone='Received' AND NOT baseline) AS received_at,
 min(at) FILTER(WHERE milestone IN('Validated','Rejected') AND NOT baseline) AS validated_at,
 min(at) FILTER(WHERE milestone='Matched' AND NOT baseline) AS matched_at,
 min(at) FILTER(WHERE milestone='Netted' AND NOT baseline) AS netted_at,
 min(at) FILTER(WHERE milestone='Rejected' AND NOT baseline) AS rejected_at,
 bool_or(baseline) AS has_baseline, count(*) FILTER(WHERE milestone='Retry scheduled') AS retries
 FROM c_process_events WHERE operation_id=o.operation_id
) m ON true LEFT JOIN LATERAL (
 SELECT count(DISTINCT entity_id) FILTER(WHERE kind='netting_sets') AS netting_outputs,
 count(DISTINCT entity_id) FILTER(WHERE kind='netting_sets' AND (body->>'instruction_required')::boolean) AS required_outputs,
 count(DISTINCT body->>'netting_set_id') FILTER(WHERE kind='settlement_instructions' AND body->>'status' IN('GENERATED','SENT','ACKNOWLEDGED')) AS generated_outputs,
 count(DISTINCT body->>'netting_set_id') FILTER(WHERE kind='settlement_instructions' AND body->>'status'='GENERATED' AND NOT baseline) AS timed_generated_outputs,
 max(at) FILTER(WHERE kind='netting_sets' OR body->>'status'='GENERATED') AS outputs_at,bool_or(baseline) AS has_baseline
 FROM c_process_events WHERE operation_id=o.operation_id AND kind IN('netting_sets','settlement_instructions')
) x ON true
 LEFT JOIN c_trade t USING(operation_id) LEFT JOIN c_business_operations b USING(operation_id);

CREATE OR REPLACE VIEW c_process_sla AS
WITH times AS (
 SELECT s.*,p.policy_id,p.label,p.target_seconds,p.warning_fraction,p.description,
 CASE p.policy_id WHEN 'validation' THEN validated_at WHEN 'matching' THEN matched_at ELSE instructions_at END AS finished_at,
 h.collected_at,h.collector_age_seconds
 FROM c_process_summary s CROSS JOIN c_sla_policy p LEFT JOIN c_evidence_health h ON true
), elapsed AS (
 SELECT *,extract(epoch FROM coalesce(finished_at,collected_at)-received_at)::double precision AS elapsed_seconds FROM times
)
SELECT *,CASE WHEN received_at IS NULL OR has_baseline OR elapsed_seconds<0 OR collected_at IS NULL THEN 'Unknown'
 WHEN policy_id='instructions' AND business_state='Instructions ready' AND finished_at IS NULL THEN 'Unknown'
 WHEN business_state='Rejected' AND policy_id<>'validation' THEN 'Excluded'
 WHEN business_state='Failed' AND finished_at IS NULL THEN 'Failed'
 WHEN finished_at IS NULL AND (collector_age_seconds>10 OR (SELECT oldest_pending_seconds FROM c_evidence_health)>10) THEN 'Stale'
 WHEN elapsed_seconds>target_seconds THEN 'Breached'
 WHEN finished_at IS NOT NULL THEN 'Met'
 WHEN elapsed_seconds>=target_seconds*warning_fraction THEN 'At risk' ELSE 'In progress' END AS sla_status
FROM elapsed;

-- State regions use source event order and end at the last collector observation.
-- Baseline imports are not historical transitions; absent history remains absent.
CREATE OR REPLACE FUNCTION c_process_timeline(selected_operation text)
RETURNS TABLE(start_time timestamptz,end_time timestamptz,state text)
LANGUAGE sql STABLE AS $$
 WITH terminal_failures AS (
 SELECT e.at,e.sequence,'Failed'::text AS state
 FROM c_process_events e JOIN c_queue q ON q.message_id=e.body->>'queue_message_id'
 WHERE e.operation_id=selected_operation AND e.kind='processing_attempts'
 AND e.milestone='Processing failed' AND NOT e.baseline AND q.status='FAILED'
 ), milestones AS (
 SELECT at,sequence,CASE milestone WHEN 'Validated' THEN 'Waiting for counterparty' ELSE milestone END AS state
 FROM c_process_events WHERE operation_id=selected_operation AND NOT baseline
 AND (kind='trades' OR milestone='Received')
 UNION ALL
 SELECT at,sequence,state FROM terminal_failures
 UNION ALL
 SELECT instructions_at,9223372036854775807,CASE WHEN required_outputs=0 THEN 'No instruction required' ELSE 'Instructions ready' END
 FROM c_process_summary WHERE operation_id=selected_operation AND instructions_at IS NOT NULL
 ), ordered AS (
 SELECT at,lead(at) OVER(ORDER BY at,sequence) AS next_at,state FROM milestones
 ), observation AS (SELECT collected_at FROM c_evidence_health)
 SELECT at,coalesce(next_at,observation.collected_at),state FROM ordered CROSS JOIN observation
 WHERE coalesce(next_at,observation.collected_at)>=at
$$;
REVOKE ALL ON FUNCTION c_process_timeline(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION c_process_timeline(text) TO grafana_c;
GRANT SELECT ON c_sla_policy,c_process_events,c_process_summary,c_process_sla TO grafana_c;

-- Four evidence intervals for the business drilldown. Instruction generation can
-- overlap netting: output and GENERATED records can precede the NETTED trade
-- marker. Open intervals end at the collector observation, never at "now".
-- Successful terminal states do not grow a display-only tail.
CREATE OR REPLACE FUNCTION c_process_stages(selected_operation text)
RETURNS TABLE(stage_order int,stage text,start_time timestamptz,end_time timestamptz,
 status text,elapsed_ms double precision,retries bigint,readiness_at timestamptz)
LANGUAGE sql STABLE AS $$
 WITH process AS (
  SELECT s.*,h.collected_at,h.collector_age_seconds,h.oldest_pending_seconds
  FROM c_process_summary s CROSS JOIN c_evidence_health h
  WHERE s.operation_id=selected_operation
 ), output_events AS (
  SELECT min(at) FILTER(WHERE milestone='Netting output recorded' AND NOT baseline) AS first_required_output_at,
         max(at) FILTER(WHERE kind='settlement_instructions' AND body->>'status'='GENERATED' AND NOT baseline) AS last_generated_at
  FROM c_process_events WHERE operation_id=selected_operation
 ), failed AS (
  SELECT min(e.at) AS at FROM c_process_events e
  JOIN c_queue q ON q.message_id=e.body->>'queue_message_id'
  WHERE e.operation_id=selected_operation AND e.kind='processing_attempts'
   AND e.milestone='Processing failed' AND NOT e.baseline AND q.status='FAILED'
 ), stages AS (
  SELECT 1 AS n,'Validation'::text AS label,p.received_at AS began,p.validated_at AS completed,
         'INGESTION'::text AS queue_stage,p.instructions_at AS readiness_at FROM process p
  UNION ALL
  SELECT 2,'Counterparty matching wait',p.validated_at,p.matched_at,'MATCHING',p.instructions_at
  FROM process p
  UNION ALL
  SELECT 3,'Netting',p.matched_at,p.netted_at,'NETTING',p.instructions_at
  FROM process p
  UNION ALL
  SELECT 4,'Instruction generation',o.first_required_output_at,
         CASE WHEN p.required_outputs>0 AND p.instructions_at IS NOT NULL THEN o.last_generated_at END,
         NULL::text,p.instructions_at
  FROM process p CROSS JOIN output_events o
 ), classified AS (
  SELECT st.*,p.collected_at,
   CASE
    WHEN p.rejected_at IS NOT NULL AND st.n>1 THEN 'Not applicable'
    WHEN st.n=4 AND p.required_outputs=0 AND p.instructions_at IS NOT NULL THEN 'Not required'
    WHEN st.began IS NULL THEN
     CASE WHEN st.completed IS NOT NULL OR p.has_baseline OR
       (st.n=4 AND p.business_state IN('Instructions ready','Netted; output evidence missing'))
       THEN 'Missing evidence' ELSE 'Not reached' END
    WHEN st.completed IS NOT NULL AND st.completed<st.began THEN 'Missing evidence'
    WHEN st.n=1 AND st.completed IS NULL AND
      (p.matched_at IS NOT NULL OR p.netted_at IS NOT NULL OR p.instructions_at IS NOT NULL)
      THEN 'Missing evidence'
    WHEN st.n=2 AND st.completed IS NULL AND
      (p.netted_at IS NOT NULL OR p.instructions_at IS NOT NULL)
      THEN 'Missing evidence'
    WHEN st.completed IS NOT NULL AND st.completed>=st.began
      AND (f.at IS NULL OR st.completed<=f.at) THEN
     CASE WHEN st.n=1 AND p.rejected_at IS NOT NULL THEN 'Rejected' ELSE 'Completed' END
    WHEN p.rejected_at IS NOT NULL AND p.rejected_at>=st.began THEN 'Rejected'
    WHEN f.at IS NOT NULL AND f.at>=st.began THEN 'Failed'
    WHEN p.business_state IN('Failed','Rejected','Instructions ready') THEN 'Missing evidence'
    WHEN st.n=4 AND p.business_state='Netted; output evidence missing' THEN 'Missing evidence'
    WHEN p.collected_at IS NULL OR p.collected_at<st.began THEN 'Missing evidence'
    WHEN p.collector_age_seconds>10 OR p.oldest_pending_seconds>10 THEN 'Stale'
    ELSE 'Observed open' END AS stage_status,
   f.at AS failed_at,p.rejected_at
  FROM stages st CROSS JOIN process p CROSS JOIN failed f
 ), bounded AS (
  SELECT c.*,
   CASE c.stage_status
    WHEN 'Completed' THEN c.completed
    WHEN 'Rejected' THEN coalesce(c.completed,c.rejected_at)
    WHEN 'Failed' THEN c.failed_at
    WHEN 'Observed open' THEN c.collected_at
    WHEN 'Stale' THEN c.collected_at
    ELSE NULL END AS ended
  FROM classified c
 )
 SELECT b.n,b.label,b.began,b.ended,b.stage_status,
  CASE WHEN b.ended>=b.began THEN extract(epoch FROM b.ended-b.began)*1000 END,
  CASE WHEN b.began IS NULL OR b.queue_stage IS NULL THEN NULL::bigint ELSE (
   SELECT count(DISTINCT e.event_id) FROM c_process_events e
   WHERE (e.operation_id=selected_operation OR
     (b.queue_stage='NETTING' AND e.operation_id IN
      (SELECT related_operation_id FROM c_operation_links WHERE operation_id=selected_operation)))
    AND NOT e.baseline AND e.milestone='Retry scheduled'
    AND e.body->>'queue_name'=b.queue_stage) END,
  CASE WHEN b.n=4 THEN b.readiness_at END
 FROM bounded b ORDER BY b.n
$$;
REVOKE ALL ON FUNCTION c_process_stages(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION c_process_stages(text) TO grafana_c;

-- Compact elapsed-time labels for the process timeline (4 ms, 1.3 s, 1m 04s, 2h 05m).
CREATE OR REPLACE FUNCTION c_format_duration(seconds double precision)
RETURNS text LANGUAGE sql IMMUTABLE AS $$
 SELECT CASE WHEN seconds IS NULL OR seconds<0 THEN NULL
  WHEN seconds<1 THEN round((seconds*1000)::numeric)::text||' ms'
  WHEN seconds<60 THEN round(seconds::numeric,1)::text||' s'
  WHEN seconds<3600 THEN floor(seconds/60)::text||'m '||lpad(floor(seconds::numeric%60)::text,2,'0')||'s'
  ELSE floor(seconds/3600)::text||'h '||lpad(floor(seconds::numeric%3600/60)::text,2,'0')||'m' END
$$;
REVOKE ALL ON FUNCTION c_format_duration(double precision) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION c_format_duration(double precision) TO grafana_c;

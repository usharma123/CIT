-- Render stored C evidence using Grafana's trace frame contract. These are
-- display rows reconstructed at query time, never application/OTel spans.
CREATE OR REPLACE FUNCTION c_waterfall(selected_operation text)
RETURNS TABLE("traceID" text,"spanID" text,"parentSpanID" text,
 "operationName" text,"serviceName" text,"startTime" double precision,
 duration double precision,tags jsonb,"serviceTags" jsonb,warnings jsonb)
LANGUAGE sql STABLE AS $function$
WITH calls AS MATERIALIZED (
 SELECT *,coalesce(started_at,finished_at-duration_ms*interval '1 millisecond',finished_at) AS begins
 FROM c_call WHERE operation_id=selected_operation
), attempts AS MATERIALIZED (
 SELECT *,started_at-greatest(coalesce(wait_seconds,0),0)*interval '1 second' AS ready_at,
 lag(finished_at) OVER(PARTITION BY message_id ORDER BY started_at,attempt) AS previous_end,
 lag(outcome) OVER(PARTITION BY message_id ORDER BY started_at,attempt) AS previous_outcome
 FROM c_attempt WHERE operation_id=selected_operation
), observation AS (
 SELECT coalesce((SELECT collected_at FROM c_evidence_health),now()) AS at
), bounds AS (
 SELECT min(begins) AS begins,max(ends) AS ends FROM (
  SELECT begins,coalesce(finished_at,greatest(begins,observation.at)) AS ends FROM calls CROSS JOIN observation
  UNION ALL SELECT ready_at,coalesce(finished_at,greatest(started_at,observation.at)) FROM attempts CROSS JOIN observation
 ) b
), attached_calls AS (
 SELECT c.*,p.call_id AS known_parent,a.attempt_id AS owner_attempt,
  CASE WHEN p.call_id IS NOT NULL THEN 'recorded parent_call_id'
       WHEN c.parent_call_id IS NOT NULL THEN 'missing or invalid parent; attached to operation'
       WHEN a.attempt_id IS NOT NULL THEN 'inferred from message ID, worker and attempt interval'
       ELSE 'operation grouping' END AS parent_evidence
 FROM calls c
 LEFT JOIN calls p ON p.call_id=c.parent_call_id AND p.depth<c.depth
 LEFT JOIN LATERAL (
  SELECT CASE WHEN count(*)=1 THEN min(attempt_id) END AS attempt_id FROM attempts a
  WHERE c.message_id=a.message_id AND c.worker=a.worker
   AND c.begins>=a.started_at AND c.begins<=coalesce(a.finished_at,(SELECT at FROM observation))
 ) a ON c.parent_call_id IS NULL
), rows(id,parent,name,service,begins,ends,measured_ms,attributes,notes) AS (
 SELECT 'operation',NULL::text,'Reconstructed operation', 'C evidence',b.begins,b.ends,NULL::double precision,
  jsonb_build_object('evidence.source','logs + committed MQ journals','evidence.kind','synthetic operation grouping',
   'operation_id',selected_operation), '[]'::jsonb FROM bounds b WHERE b.begins IS NOT NULL
 UNION ALL
 SELECT 'call:'||c.call_id,
  CASE WHEN known_parent IS NOT NULL THEN 'call:'||known_parent
       WHEN owner_attempt IS NOT NULL THEN 'process:'||owner_attempt ELSE 'operation' END,
  c.component||CASE WHEN evidence_state!='paired' THEN ' ['||evidence_state||']' ELSE '' END,
  c.stage,c.begins,coalesce(c.finished_at,greatest(c.begins,o.at)),c.duration_ms,
  jsonb_strip_nulls(jsonb_build_object('evidence.source','component logs','evidence.state',c.evidence_state,
   'evidence.parent',c.parent_evidence,'call_id',c.call_id,'parent_call_id',c.parent_call_id,
   'message_id',c.message_id,'published_message_id',c.published_message_id,'worker',c.worker,
   'outcome',c.outcome,'error_type',c.error_type,'error',c.outcome='exception')),
  to_jsonb(array_remove(ARRAY[
   CASE WHEN c.evidence_state!='paired' THEN c.evidence_state||'; interval is reconstructed, not proof of completion' END,
   CASE WHEN c.parent_call_id IS NOT NULL AND known_parent IS NULL THEN 'Recorded parent missing or invalid; no caller inferred' END
  ],NULL))
 FROM attached_calls c CROSS JOIN observation o WHERE c.begins IS NOT NULL
 UNION ALL
 SELECT 'attempt:'||a.attempt_id,'operation',a.stage||' / attempt '||a.attempt||' / '||coalesce(a.outcome,'open'),a.stage,
  CASE WHEN a.previous_outcome='retried' AND a.previous_end<a.ready_at THEN a.previous_end ELSE a.ready_at END,
  coalesce(a.finished_at,greatest(a.started_at,o.at)),NULL,
  jsonb_strip_nulls(jsonb_build_object('evidence.source','MQ journal','evidence.kind','synthetic attempt grouping',
   'attempt_id',a.attempt_id,'message_id',a.message_id,'attempt',a.attempt,'worker',a.worker,
   'outcome',a.outcome,'reason',a.reason,'error',a.outcome IN('failed','retried','abandoned'))),
  CASE WHEN a.finished_at IS NULL THEN '["No committed finish; bar extends to collector observation"]'::jsonb ELSE '[]'::jsonb END
 FROM attempts a CROSS JOIN observation o
 UNION ALL
 SELECT 'wait:'||a.attempt_id,'attempt:'||a.attempt_id,'MQ READY WAIT',a.stage,a.ready_at,a.started_at,NULL,
  jsonb_build_object('evidence.source','MQ journal','evidence.kind','ready-to-claim interval','message_id',a.message_id,
   'attempt_id',a.attempt_id,'wait_seconds',a.wait_seconds),'[]'::jsonb FROM attempts a WHERE a.wait_seconds>0
 UNION ALL
 SELECT 'backoff:'||a.attempt_id,'attempt:'||a.attempt_id,'MQ RETRY DELAY',a.stage,a.previous_end,a.ready_at,NULL,
  jsonb_build_object('evidence.source','MQ journal','evidence.kind','previous retry finish to next ready time',
   'message_id',a.message_id,'attempt_id',a.attempt_id),'[]'::jsonb
 FROM attempts a WHERE a.previous_outcome='retried' AND a.previous_end<a.ready_at
 UNION ALL
 SELECT 'process:'||a.attempt_id,'attempt:'||a.attempt_id,'MQ PROCESS / '||coalesce(a.outcome,'open'),a.stage,
  a.started_at,coalesce(a.finished_at,greatest(a.started_at,o.at)),a.duration_seconds*1000,
  jsonb_strip_nulls(jsonb_build_object('evidence.source','committed MQ attempt journal','message_id',a.message_id,
   'attempt_id',a.attempt_id,'worker',a.worker,'outcome',a.outcome,'reason',a.reason,
   'error',a.outcome IN('failed','retried','abandoned'))),
  CASE WHEN a.finished_at IS NULL THEN '["No committed finish; duration is an observed lower bound"]'::jsonb ELSE '[]'::jsonb END
 FROM attempts a CROSS JOIN observation o
)
SELECT md5('c-reconstruction:'||selected_operation),left(md5(selected_operation||':'||id),16),
 CASE WHEN parent IS NOT NULL THEN left(md5(selected_operation||':'||parent),16) END,
 name,service,extract(epoch FROM begins)::double precision*1000,
 greatest(coalesce(measured_ms,extract(epoch FROM ends-begins)::double precision*1000),0),
 (SELECT jsonb_agg(jsonb_build_object('key',key,'value',value) ORDER BY key)
  FROM jsonb_each(attributes||jsonb_build_object('operation_id',selected_operation,'reconstructed',true))),
 '[{"key":"approach","value":"C: reconstructed from logs and journals"}]'::jsonb,
 notes||CASE WHEN ends<begins THEN '["Clock/order inconsistency: negative duration clamped to zero"]'::jsonb ELSE '[]'::jsonb END
FROM rows ORDER BY (parent IS NULL) DESC,begins,id;
$function$;
REVOKE ALL ON FUNCTION c_waterfall(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION c_waterfall(text) TO grafana_c;

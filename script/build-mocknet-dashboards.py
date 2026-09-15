#!/usr/bin/env python3
"""Generate version-controlled Grafana dashboards; no runtime plugin dependency."""
import json, pathlib, urllib.parse
OUT=pathlib.Path(__file__).resolve().parents[1]/'observability/grafana/dashboards'
P={'type':'prometheus','uid':'mocknet-prometheus'}
S={'type':'grafana-postgresql-datasource','uid':'mocknet-operations'}
T={'type':'tempo','uid':'mocknet-tempo'}
L={'type':'loki','uid':'mocknet-loki'}
filters='service=~"$service",environment=~"$environment",version=~"$version",app_instance=~"$app_instance"'
# These gauges describe one shared database, not work owned by each exporter.
queue_filters='service=~"$service",environment=~"$environment",stage=~"$stage",stage!="DEAD_LETTER"'
timefilter='$__timeFilter(accepted_at)'
business="(${business_id:sqlstring}='' OR business_id=${business_id:sqlstring} OR operation_id=${business_id:sqlstring})"
scope=f'{timefilter} AND {business}'
vars=[{'name':n,'label':label,'type':'query','datasource':P,'query':f'label_values(mocknet_snapshot_timestamp, {key})','refresh':1,'includeAll':True,'allValue':'.*','current':{'text':'All','value':'$__all'},'multi':False} for n,label,key in [('environment','Environment','environment'),('service','Service','service'),('version','Version','version'),('app_instance','Instance','app_instance')]]
vars+=[{'name':'stage','label':'Stage','type':'custom','query':'INGESTION,MATCHING,NETTING,SETTLEMENT,DEAD_LETTER','includeAll':True,'allValue':'.*','current':{'text':'All','value':'$__all'}}, {'name':'business_id','label':'Trade or operation ID','type':'textbox','query':'','current':{'text':'','value':''}}]
links=[{'title':title,'type':'link','url':'/d/'+uid,'includeVars':True,'keepTime':True} for title,uid in [('Service overview','mocknet-traces'),('Stage diagnostics','mocknet-stages'),('Operation investigation','mocknet-operation')]]
links.append({'title':'Compare Approach B','type':'link','url':'/d/mocknet-b-overview','keepTime':True})
links.append({'title':'Compare Approach C','type':'link','url':'http://localhost:3302/d/mocknet-c-overview','keepTime':True})
def dash(uid,title):return {'uid':uid,'title':title,'schemaVersion':40,'version':1,'editable':False,'tags':['mocknet','L3','Approach A','provisioned'],'timezone':'browser','refresh':'10s','time':{'from':'now-30m','to':'now'},'links':links,'templating':{'list':vars},'panels':[],'annotations':{'list':[]}}
def panel(d,title,typ,ds,x,y,w,h,desc=''):
 p={'id':len(d['panels'])+1,'title':title,'type':typ,'gridPos':{'x':x,'y':y,'w':w,'h':h},'description':desc,'fieldConfig':{'defaults':{'color':{'mode':'palette-classic'},'custom':{'lineWidth':2,'fillOpacity':8}},'overrides':[]},'options':{},'targets':[]}
 if ds:p['datasource']=ds
 d['panels'].append(p);return p
def text(d,title,body,y,h=3):
 p=panel(d,title,'text',None,0,y,24,h);p['options']={'mode':'markdown','content':body};return p
def prom(d,title,expr,x,y,w=12,h=7,unit='short',stat=False,desc=''):
 p=panel(d,title,'stat' if stat else 'timeseries',P,x,y,w,h,desc);p['targets']=[{'refId':'A','expr':expr,'legendFormat':'{{stage}} {{outcome}} {{state}}','instant':stat,'range':not stat}];p['fieldConfig']['defaults']['unit']=unit
 p['options']={'reduceOptions':{'calcs':['lastNotNull'],'fields':'','values':False},'textMode':'auto','colorMode':'value'} if stat else {'legend':{'displayMode':'list','placement':'bottom'},'tooltip':{'mode':'multi'}}
 return p
def sql(d,title,q,x,y,w=24,h=8,stat=False,desc=''):
 p=panel(d,title,'stat' if stat else 'table',S,x,y,w,h,desc);p['targets']=[{'refId':'A','rawSql':q,'format':'table','editorMode':'code','rawQuery':True}];p['options']={'showHeader':True,'cellHeight':'sm','footer':{'show':False}} if not stat else {'reduceOptions':{'calcs':['lastNotNull'],'fields':'','values':False},'colorMode':'value','textMode':'auto'}
 return p
def fieldlink(p,field,url,title):p['fieldConfig']['overrides'].append({'matcher':{'id':'byName','options':field},'properties':[{'id':'links','value':[{'title':title,'url':url}]}]})
selection='&var-environment=${environment:percentencode}&var-service=${service:percentencode}&var-version=${version:percentencode}&var-app_instance=${app_instance:percentencode}&var-stage=${stage:percentencode}'
opurl='/d/mocknet-operation?${__url_time_range}&var-business_id=${__value.raw}'+selection
traceurl='/d/mocknet-operation?${__url_time_range}&var-trace_id=${__value.raw}&var-business_id=${business_id:percentencode}'+selection
def statuscolors(p):
 p['fieldConfig']['overrides'].append({'matcher':{'id':'byName','options':'outcome'},'properties':[{'id':'custom.cellOptions','value':{'type':'color-text'}},{'id':'mappings','value':[{'type':'value','options':{k:{'color':c,'index':i} for i,(k,c) in enumerate([('FAILED','red'),('REJECTED','orange'),('COMPLETED','green'),('AWAITING_COUNTERPARTY','blue'),('retried','orange'),('failed','red'),('completed','green')])}}]}]})
# Overview: start with cases and causes. Infrastructure health lives in stage diagnostics.
D=dash('mocknet-traces','Approach A | Service overview')
D['description']='Find affected operations, locate the failing or delayed stage, and open the operation history. Database panels show current state for the selected admission interval; metric panels show the selected interval.'
p=sql(D,'Operations to investigate',f"""SELECT o.business_id, o.outcome,
    COALESCE(active_queue.stage,last_attempt.stage) AS stage, last_attempt.reason, o.last_progress_at, o.operation_id, o.trace_id, (extract(epoch FROM o.accepted_at)*1000-60000)::bigint AS trace_from
    FROM support.operations o
    LEFT JOIN LATERAL (
        SELECT a.stage,a.reason FROM support.attempts a
        WHERE a.operation_id=ANY(ARRAY[o.operation_id] || ARRAY(SELECT related_operation_id FROM support.operation_links WHERE operation_id=o.operation_id))
        ORDER BY CASE WHEN a.outcome='failed' THEN 0 WHEN a.outcome='processing' THEN 1 ELSE 2 END,
                 a.claimed_at DESC LIMIT 1
    ) last_attempt ON true
    LEFT JOIN LATERAL (
        SELECT q.stage FROM support.queue_messages q WHERE q.operation_id=o.operation_id
        AND q.status IN ('NEW','PROCESSING') AND q.stage<>'DEAD_LETTER'
        ORDER BY CASE q.status WHEN 'PROCESSING' THEN 0 ELSE 1 END,q.created_at LIMIT 1
    ) active_queue ON true
    WHERE {business} AND ({timefilter} OR o.outcome IN ('QUEUED','PROCESSING','PENDING') OR ${{business_id:sqlstring}}<>'')
        AND COALESCE(active_queue.stage,last_attempt.stage,'') ~ '^(${{stage:regex}})$' AND (o.outcome IN ('FAILED','REJECTED','QUEUED','PROCESSING','PENDING')
        OR ${{business_id:sqlstring}}<>'')
    ORDER BY CASE o.outcome WHEN 'PROCESSING' THEN 0 WHEN 'QUEUED' THEN 1 WHEN 'PENDING' THEN 2 WHEN 'FAILED' THEN 3 ELSE 4 END,
        o.last_progress_at ASC LIMIT 50""",0,0,24,9,
    desc='Current unfinished processing is always included, even when accepted before the selected interval. Failures/rejections use the admission interval. An explicit ID searches all admissions. Oldest unfinished work comes first, up to 50 rows. Details opens admission through now for the current operation, independently of the historical chart interval. Current state is from the one local database.')
fieldlink(p,'operation_id','/d/mocknet-operation?from=${__data.fields.trace_from}&to=now&var-business_id=${__value.raw}&var-trace_id=${__data.fields.trace_id}'+selection,'Investigate');statuscolors(p)
p['fieldConfig']['overrides'].append({'matcher':{'id':'byName','options':'trace_from'},'properties':[{'id':'custom.hidden','value':True}]})
p['fieldConfig']['overrides'].append({'matcher':{'id':'byName','options':'trace_id'},'properties':[{'id':'custom.hidden','value':True}]})
for field,label,width in [('business_id','Trade',None),('outcome','Current state',120),('stage','Stage',100),('reason','Reason',180),('last_progress_at','Last progress',190),('operation_id','Details',100)]:
    properties=[{'id':'displayName','value':label}]
    if width is not None: properties.append({'id':'custom.width','value':width})
    if field=='business_id': properties.append({'id':'noValue','value':'Missing trade ID'})
    if field=='operation_id': properties.append({'id':'custom.cellOptions','value':{'type':'data-links'}})
    p['fieldConfig']['overrides'].append({'matcher':{'id':'byName','options':field},'properties':properties})
f=filters+',stage=~"$stage",stage!="DEAD_LETTER"'
prom(D,'Where work is waiting',f'max by(stage)(mocknet_queue_oldest_seconds{{{queue_filters},state="ready"}})',0,9,12,8,'s',desc='Oldest ready message per stage, excluding scheduled retry delay. Shared database scope; version/instance do not partition queues. Missing telemetry is not zero.')['targets'][0]['legendFormat']='{{stage}}'
prom(D,'Failed and retried attempts',f'sum by(stage,outcome)(rate(mocknet_processing_seconds_count{{{f},outcome=~"failed|retried"}}[$__rate_interval]))',12,9,12,8,'ops',desc='Failed means terminal attempt failure. Retried means processing will try again; investigate the operation for its final outcome.')['targets'][0]['legendFormat']='{{stage}} {{outcome}}'
p=sql(D,'Failure reasons',"SELECT stage,reason,outcome,count(DISTINCT operation_id) AS affected_operations,count(*) AS attempts,max(finished_at) AS last_seen FROM support.attempts WHERE $__timeFilter(claimed_at) AND stage ~ '^(${stage:regex})$' AND outcome IN ('failed','retried','abandoned') AND (${business_id:sqlstring}='' OR business_id=${business_id:sqlstring} OR operation_id=${business_id:sqlstring}) GROUP BY stage,reason,outcome ORDER BY affected_operations DESC LIMIT 20",0,17,16,8,
    desc='Groups committed attempt failures by stage and reason. One operation can appear in multiple signatures. Retry counts are separate from terminal failures.')
statuscolors(p)
p=sql(D,'Outcome summary',f"SELECT outcome AS state,count(*) AS operations,count(*) FILTER (WHERE recovered) AS recovered FROM support.operations WHERE {scope} GROUP BY outcome ORDER BY count(*) DESC",16,17,8,8,
    desc='Current outcome of submissions accepted in the selected interval. Recovered is a subset, not another operation. COMPLETED means netting and instruction generation finished; external settlement is outside this service.')
prom(D,'Waiting work by stage',f'max by(stage,state)(mocknet_queue_messages{{{queue_filters},state=~"ready|scheduled|processing"}})',0,25,12,8,desc='Ready, scheduled retry and active processing are separate. Shared database gauges use max across exporters to avoid counting the same queue twice; not a sum across independent databases.')['targets'][0]['legendFormat']='{{stage}} {{state}}'
prom(D,'Processing duration by stage · p95',f'histogram_quantile(0.95,sum by(le,stage)(rate(mocknet_processing_seconds_bucket{{{f}}}[$__rate_interval])))',12,25,12,8,'s',desc='Handler duration excludes ready queue waiting. Compare it with queue age to distinguish slow execution from work not being claimed.')['targets'][0]['legendFormat']='{{stage}}'
# Stage diagnosis
E=dash('mocknet-stages','Approach A | Queue and stage diagnostics')
text(E,'Runbook • locate the bottleneck','**Rising queue age:** check workers and database waiters. **Long handler time:** inspect SQL and the first error in its trace. **Retries:** read the reason and final disposition.\n\nCheck snapshot age before trusting queue values. Alert history includes recovered incidents. Stalls and connection-pressure controls expire automatically.',0,4)
f=filters+',stage=~"$stage",stage!="DEAD_LETTER"'
prom(E,'Queue depth • ready, scheduled retries, processing',f'max by(stage,state)(mocknet_queue_messages{{{queue_filters},state=~"ready|scheduled|processing"}})',0,4,desc='Shared database scope. max avoids duplicate snapshots from multiple app instances; version/instance filters apply to handler metrics, not shared queues.')
prom(E,'Oldest work • waiting versus processing',f'max by(stage,state)(mocknet_queue_oldest_seconds{{{queue_filters}}})',12,4,unit='s')
prom(E,'Processing rate',f'sum by(stage,outcome)(rate(mocknet_processing_seconds_count{{{f}}}[$__rate_interval]))',0,11,unit='ops')
prom(E,'Handler duration • p50 and p95 by stage',f'histogram_quantile(0.95,sum by(le,stage)(rate(mocknet_processing_seconds_bucket{{{f}}}[$__rate_interval])))',12,11,unit='s')['title']='Handler duration • p95 by stage'
prom(E,'Database pool • active / idle / pending',f'hikaricp_connections_active{{{filters}}}',0,18)['targets']=[{'refId':r,'expr':f'hikaricp_connections_{k}{{{filters}}}','legendFormat':k,'range':True} for r,k in zip('ABC',['active','idle','pending'])]
prom(E,'Database connection acquisition • maximum',f'hikaricp_connections_acquire_seconds_max{{{filters}}}',12,18,unit='s')
prom(E,'JVM heap used / max',f'sum(jvm_memory_used_bytes{{{filters},area="heap"}})',0,25,unit='bytes')['targets']=[{'refId':r,'expr':f'sum(jvm_memory_{k}_bytes{{{filters},area="heap"}})','legendFormat':k,'range':True} for r,k in zip('AB',['used','max'])]
prom(E,'GC pause • seconds / second',f'sum(rate(jvm_gc_pause_seconds_sum{{{filters}}}[$__rate_interval]))',12,25,unit='s')
prom(E,'Configured workers',f'mocknet_worker_configured{{{f}}}',0,32,8,5,stat=True)
prom(E,'Worker loop heartbeat age',f'time()-mocknet_worker_heartbeat_timestamp{{{f}}}',8,32,8,5,'s',True, 'Latest poll from any worker in the stage; not proof that every configured worker is alive.')
prom(E,'Paused consumers',f'mocknet_worker_paused{{{f}}}',16,32,8,5,stat=True)
p=sql(E,'Failure signatures • committed attempt history',"SELECT stage,reason,outcome,count(*) AS attempts,count(DISTINCT operation_id) AS operations,max(finished_at) AS last_seen FROM support.attempts WHERE $__timeFilter(claimed_at) AND stage ~ '^(${stage:regex})$' AND outcome IN ('failed','retried','abandoned') GROUP BY stage,reason,outcome ORDER BY attempts DESC LIMIT 30",0,37,24,7);statuscolors(p)
p=sql(E,'Slow / failed / recovered attempts • trace drilldown',"SELECT claimed_at,business_id,stage,attempt_number AS attempt,outcome,round(wait_seconds::numeric,3) AS wait_s,round(processing_seconds::numeric,3) AS process_s,reason,trace_id FROM support.attempts WHERE $__timeFilter(claimed_at) AND stage ~ '^(${stage:regex})$' ORDER BY claimed_at DESC LIMIT 100",0,44,24,10);fieldlink(p,'trace_id',traceurl,'Open trace');fieldlink(p,'business_id','/d/mocknet-operation?${__url_time_range}&var-business_id=${__value.raw}','Investigate trade');statuscolors(p)
prom(E,'Telemetry scrape health','up{job=~"mocknet|collector|tempo|loki|prometheus"}',0,54)['targets'][0]['legendFormat']='{{job}}'
prom(E,'Collector persistent export backlog','otelcol_exporter_queue_size',12,54)['targets'][0]['legendFormat']='{{exporter}}'
prom(E,'Collector rejected / failed exports','sum by(exporter)(rate(otelcol_exporter_send_failed_spans[1m]))',0,61)['targets'].append({'refId':'B','expr':'sum by(exporter)(rate(otelcol_exporter_send_failed_log_records[1m]))','legendFormat':'{{exporter}} logs','range':True})
prom(E,'Queue data age',f'time()-mocknet_snapshot_timestamp{{{filters}}}',12,61,unit='s')
# Alert history remains visible after an incident recovers.
for item in E['panels']:
 if item['gridPos']['y']>=11: item['gridPos']['y']+=7
p=panel(E,'Incident alert history • gaps mean no recorded firing state','state-timeline',P,0,11,24,7)
p['targets']=[{'refId':'A','expr':'ALERTS{alertstate="firing"}','legendFormat':'{{alertname}} {{stage}}','range':True}]
p['options']={'mergeValues':True,'showValue':'auto','legend':{'displayMode':'list','placement':'bottom'}}
p['fieldConfig']['defaults'].update({'color':{'mode':'fixed','fixedColor':'red'},'mappings':[{'type':'value','options':{'1':{'text':'FIRING','color':'red'}}}]})
# All queues, including the unused settlement path and parked dead letters.
for item in E['panels']:
 if item['gridPos']['y']>=4: item['gridPos']['y']+=7
p=sql(E,'Queue inventory · current state',"SELECT stage,path,ready,scheduled,processing,dead_letters,oldest_ready_s,oldest_processing_s FROM support.queue_health WHERE stage ~ '^(${stage:regex})$' ORDER BY CASE stage WHEN 'INGESTION' THEN 1 WHEN 'MATCHING' THEN 2 WHEN 'NETTING' THEN 3 WHEN 'SETTLEMENT' THEN 4 ELSE 5 END",0,4,24,7,
 desc='Current shared database state, regardless of incident interval or version/instance selection. Empty queues remain visible. Blank ages mean no message in that state, not zero latency. Ready age excludes intentional retry delay. Click a queue to narrow the diagnosis.')
fieldlink(p,'stage','/d/mocknet-stages?${__url_time_range}&var-stage=${__value.raw}','Inspect queue')
for name,label in [('stage','Queue'),('path','Consumer path'),('ready','Ready'),('scheduled','Retry scheduled'),('processing','Processing'),('dead_letters','Dead letters'),('oldest_ready_s','Oldest ready'),('oldest_processing_s','Longest processing')]:
 props=[{'id':'displayName','value':label}]
 if name.endswith('_s'): props += [{'id':'unit','value':'s'},{'id':'noValue','value':'—'}]
 p['fieldConfig']['overrides'].append({'matcher':{'id':'byName','options':name},'properties':props})
# Put actionable current messages directly below the queue summary.
for item in E['panels']:
 if item['gridPos']['y']>=11: item['gridPos']['y']+=10
p=sql(E,'Current work and dead letters · independent of time range',"""WITH work AS (
 SELECT q.*, CASE WHEN stage='DEAD_LETTER' THEN 'dead_letter' WHEN status='PROCESSING' THEN 'processing'
   WHEN available_at>now() THEN 'scheduled' ELSE 'ready' END AS state
 FROM support.queue_messages q WHERE status IN ('NEW','PROCESSING') AND stage ~ '^(${stage:regex})$'
 AND (${business_id:sqlstring}='' OR business_id=${business_id:sqlstring} OR operation_id=${business_id:sqlstring})
 ORDER BY CASE WHEN stage='DEAD_LETTER' THEN 3 WHEN status='PROCESSING' THEN 0 WHEN available_at<=now() THEN 1 ELSE 2 END,
 CASE WHEN stage='DEAD_LETTER' THEN -extract(epoch FROM created_at) ELSE extract(epoch FROM created_at) END LIMIT 100
)
SELECT CASE WHEN q.stage='DEAD_LETTER' THEN COALESCE(failed.stage,'Unknown') || ' → DLQ' ELSE q.stage END AS stage,
 q.business_id,q.state,extract(epoch FROM now()-q.created_at) AS queued_age_s,
 CASE WHEN q.stage='DEAD_LETTER' THEN failed.failed_attempts ELSE q.failed_attempts END AS failed_attempts,
 a.reason,COALESCE(q.worker_name,failed.worker_name) AS worker_name,q.operation_id,q.trace_id,
 (extract(epoch FROM COALESCE((SELECT min(created_at) FROM support.queue_messages root WHERE root.operation_id=q.operation_id),q.created_at))*1000-60000)::bigint AS trace_from
FROM work q LEFT JOIN LATERAL (
 SELECT stage,failed_attempts,worker_name FROM support.queue_messages source WHERE q.stage='DEAD_LETTER' AND source.operation_id=q.operation_id AND source.status='FAILED' ORDER BY source.completed_at DESC LIMIT 1
) failed ON true LEFT JOIN LATERAL (
 SELECT reason FROM support.attempts a WHERE a.operation_id=q.operation_id AND (q.stage='DEAD_LETTER' OR a.queue_message_id=q.id) AND reason IS NOT NULL ORDER BY claimed_at DESC LIMIT 1
) a ON true
ORDER BY CASE q.state WHEN 'processing' THEN 0 WHEN 'ready' THEN 1 WHEN 'scheduled' THEN 2 ELSE 3 END,
 CASE WHEN q.state='dead_letter' THEN -extract(epoch FROM q.created_at) ELSE extract(epoch FROM q.created_at) END""",0,11,24,10,
 desc='Read-only metadata, never consumes or acknowledges messages. Oldest active work first, then newest parked dead letters; up to 100 rows. Queued age includes scheduled retry time; for dead letters it starts at parking. DLQ origin, failures and worker come from the most recent failed source message for that operation. Details opens the operation from its admission through now, even when the incident interval excludes it. Source is the current local database.')
current_opurl='/d/mocknet-operation?from=${__data.fields.trace_from}&to=now&var-business_id=${__value.raw}&var-trace_id=${__data.fields.trace_id}'+selection
fieldlink(p,'operation_id',current_opurl,'Investigate')
for name,label,width in [('stage','Queue / DLQ origin',150),('business_id','Trade',None),('state','State',100),('queued_age_s','Queue age',100),('failed_attempts','Failures',80),('reason','Last failure reason',180),('worker_name','Last worker',140),('operation_id','Details',100)]:
 props=[{'id':'displayName','value':label}]
 if width: props.append({'id':'custom.width','value':width})
 if name=='queued_age_s': props.append({'id':'unit','value':'s'})
 if name=='operation_id': props.append({'id':'custom.cellOptions','value':{'type':'data-links'}})
 p['fieldConfig']['overrides'].append({'matcher':{'id':'byName','options':name},'properties':props})
p['fieldConfig']['overrides'].append({'matcher':{'id':'byName','options':'trace_from'},'properties':[{'id':'custom.hidden','value':True}]})
p['fieldConfig']['overrides'].append({'matcher':{'id':'byName','options':'trace_id'},'properties':[{'id':'custom.hidden','value':True}]})
# Flow is measured from committed durable messages, not from sampled spans or attempts.
for item in E['panels']:
 if item['title']=='Processing rate':
  item['title']='Queue arrivals and terminal departures · per minute'
  item['datasource']=S
  item['description']='Committed messages per complete/partial calendar minute in the selected interval. Terminal departure means DONE or FAILED, counted once per queue message; retried attempts are not departures. Failures are shown separately from successful/rejected departures. First/last buckets may be partial. Shared local database scope; no version/instance partition.'
  item['targets']=[{'refId':'A','format':'time_series','rawQuery':True,'editorMode':'code','rawSql':"""WITH events AS (
   SELECT created_at AS at,stage,'arrived' AS flow FROM support.queue_messages WHERE $__timeFilter(created_at)
   UNION ALL
   SELECT completed_at,stage,CASE WHEN status='FAILED' THEN 'failed' WHEN outcome='rejected' THEN 'rejected' ELSE 'completed' END
   FROM support.queue_messages WHERE status IN ('DONE','FAILED') AND $__timeFilter(completed_at)
  ) SELECT date_trunc('minute',at) AS time,stage || ' ' || flow AS metric,count(*)::double precision AS value
  FROM events WHERE stage ~ '^(${stage:regex})$' AND stage<>'DEAD_LETTER' GROUP BY 1,2 ORDER BY 1,2"""}]
  item['fieldConfig']['defaults']['unit']='short'
  item['fieldConfig']['defaults']['custom'].update({'drawStyle':'line','lineInterpolation':'stepAfter','showPoints':'always','pointSize':4,'spanNulls':False})
  item['description'] += ' Select one Stage for arrival/departure comparison. Missing buckets contain no recorded events; gaps are not connected.'
# Handler-start wait distribution complements the age of work still waiting.
for item in E['panels']:
 if item['gridPos']['y']>=56: item['gridPos']['y']+=7
prom(E,'Ready wait before processing · p95',f'histogram_quantile(0.95,sum by(le,stage)(rate(mocknet_queue_wait_seconds_bucket{{{f}}}[$__rate_interval])))',0,56,12,7,'s',desc='Wait from ready time to claim, recorded only after an attempt finishes. Delayed retries are excluded. Read together with oldest-ready age: work never claimed has no histogram sample.')['targets'][0]['legendFormat']='{{stage}}'
prom(E,'Retry share of finished attempts',f'sum by(stage)(rate(mocknet_processing_seconds_count{{{f},outcome="retried"}}[$__rate_interval])) / sum by(stage)(rate(mocknet_processing_seconds_count{{{f}}}[$__rate_interval]))',12,56,12,7,'percentunit',desc='Attempt ratio, not the percentage of failed business operations. A retry can recover. Undefined when there are no finished attempts.')['targets'][0]['legendFormat']='{{stage}}'
# Operation investigation
F=dash('mocknet-operation','Approach A | Operation investigation')
F['templating']['list']=vars+[{'name':'trace_id','label':'Trace ID','type':'query','datasource':S,'refresh':1,'query':f"WITH selected AS (SELECT operation_id FROM support.operations WHERE {business} ORDER BY accepted_at DESC LIMIT 50), related AS (SELECT operation_id FROM selected UNION SELECT related_operation_id FROM support.operation_links WHERE operation_id IN (SELECT operation_id FROM selected)) SELECT DISTINCT trace_id AS __text,trace_id AS __value FROM support.queue_messages WHERE operation_id IN (SELECT operation_id FROM related) AND trace_id IS NOT NULL ORDER BY 1 LIMIT 100",'current':{'text':'','value':''}}, {'name':'traceql','label':'Advanced TraceQL','type':'textbox','query':'{ resource.service.name = "mocknet" && name = "QueueMessage.process" }','current':{'text':'{ resource.service.name = "mocknet" && name = "QueueMessage.process" }','value':'{ resource.service.name = "mocknet" && name = "QueueMessage.process" }'}}]
p=sql(F,'Operation and trace',f"SELECT trace_id,business_id,outcome,last_progress_at,round(terminal_latency_seconds::numeric,3) AS terminal_seconds,now() AS observed_at,operation_id,(extract(epoch FROM accepted_at)*1000-60000)::bigint AS trace_from FROM support.operations WHERE {business} ORDER BY accepted_at DESC LIMIT 50",0,2,24,5,
 desc='Current database outcome and originating request trace. Click the full Trace ID to select this operation and load its waterfall. COMPLETED means local instruction generation, not external settlement. An ID proves correlation; an expired or not-yet-exported trace may be unavailable in Tempo.')
request_trace_url='/d/mocknet-operation?from=${__data.fields.trace_from}&to=now&var-trace_id=${__value.raw}&var-business_id=${__data.fields.operation_id}'+selection
fieldlink(p,'trace_id',request_trace_url,'Open request waterfall');statuscolors(p)
for name,label,width in [('trace_id','Trace ID',285),('business_id','Trade',None),('outcome','State',115),('last_progress_at','Last progress',175),('terminal_seconds','Total time',100),('observed_at','Observed at',175)]:
 props=[{'id':'displayName','value':label}]
 if width: props.append({'id':'custom.width','value':width})
 if name=='terminal_seconds': props.append({'id':'unit','value':'s'})
 p['fieldConfig']['overrides'].append({'matcher':{'id':'byName','options':name},'properties':props})
for name in ['operation_id','trace_from']:
 p['fieldConfig']['overrides'].append({'matcher':{'id':'byName','options':name},'properties':[{'id':'custom.hidden','value':True}]})
related="WITH selected AS (SELECT operation_id FROM support.operations WHERE "+business+" ORDER BY accepted_at DESC LIMIT 50), related AS (SELECT operation_id FROM selected UNION SELECT related_operation_id FROM support.operation_links WHERE operation_id IN (SELECT operation_id FROM selected)) "
p=sql(F,'Related operations • both sides of a matched trade',related+"SELECT o.trace_id,o.business_id,o.operation_id,o.outcome FROM support.operations o JOIN related r USING(operation_id) ORDER BY o.accepted_at LIMIT 100",0,12,24,6);fieldlink(p,'trace_id',traceurl,'Open request trace');fieldlink(p,'operation_id',opurl,'Select operation');statuscolors(p)
p=sql(F,'Attempt timeline • persisted across retries and process restarts',related+"SELECT a.trace_id,a.claimed_at,a.stage,a.attempt_number AS attempt,a.outcome,round(a.wait_seconds::numeric,3) AS ready_wait_s,round(a.processing_seconds::numeric,3) AS handler_s,a.reason,a.worker,a.service_version FROM support.attempts a JOIN related r USING(operation_id) ORDER BY a.claimed_at LIMIT 200",0,18,24,10);fieldlink(p,'trace_id',traceurl,'Open attempt waterfall');statuscolors(p)
p=sql(F,'Queue disposition • includes work with no consumer span yet',related+"SELECT q.trace_id,q.id,q.stage,q.status,q.outcome,q.failed_attempts,q.created_at,q.available_at,q.claimed_at,q.completed_at FROM support.queue_messages q JOIN related r USING(operation_id) ORDER BY q.created_at LIMIT 200",0,28,24,8);fieldlink(p,'trace_id',traceurl,'Open originating trace')
p=panel(F,'Selected trace waterfall','traces',T,0,36,24,15);p['targets']=[{'refId':'A','queryType':'traceql','query':'$trace_id','tableType':'traces'}]
p=panel(F,'Correlated application logs','logs',L,0,51,24,12,'Select a trace for exact log correlation. With no trace selected, shows queue events for the chosen business or operation ID. IDs are structured metadata, not stream labels.');p['targets']=[{'refId':'A','expr':'{service_name=~".+",service_name=~"$service",deployment_environment_name=~"$environment"} | trace_id =~ "${trace_id:regex}.*" |~ ""','queryType':'range'}]
# Filter IDs with structured metadata, escaping through Grafana JSON interpolation.
p['targets'][0]['expr']='{service_name=~".+",service_name=~"$service",deployment_environment_name=~"$environment"} | trace_id =~ "${trace_id:regex}.*" | operation_id =~ ".*" | business_id =~ ".*"'
# Logs use operation_id/business_id OR expression; an empty selection matches all correlated events.
p['targets'][0]['expr']='{service_name=~".+",service_name=~"$service",deployment_environment_name=~"$environment"} | trace_id =~ "${trace_id:regex}.*" | operation_id =~ "${business_id:regex}.*" or business_id =~ "${business_id:regex}.*"'
p['targets'][0]['expr'] += ' | line_format "{{.stage}} {{.outcome}} {{.reason}} | {{ __line__ }}"'
p['options']={'showTime':True,'showLabels':False,'wrapLogMessage':True,'sortOrder':'Descending','enableLogDetails':True,'dedupStrategy':'none'}
p=panel(F,'Advanced trace search','table',T,0,63,24,10);p['targets']=[{'refId':'A','queryType':'traceql','query':'$traceql','limit':50,'tableType':'traces'}];p['options']={'showHeader':True};fieldlink(p,'traceID',traceurl,'Open waterfall')
# Keep the selected waterfall directly below the trace selector, not several tables away.
positions={'Related operations • both sides of a matched trade':(40,6),
 'Attempt timeline • persisted across retries and process restarts':(22,8),
 'Queue disposition • includes work with no consumer span yet':(46,8),
 'Selected trace waterfall':(7,15),'Correlated application logs':(30,10),'Advanced trace search':(54,10)}
for item in F['panels']:
 if item['title'] in positions:
  y,h=positions[item['title']];item['gridPos'].update(y=y,h=h)
 if item['type']=='table' and item['title']!='Operation and trace':
  item['fieldConfig']['overrides'].append({'matcher':{'id':'byName','options':'trace_id'},'properties':[{'id':'displayName','value':'Trace ID'},{'id':'custom.width','value':285}]})
for item in F['panels']: item['gridPos']['y']-=2
for dashboard in [D,E,F]:
 dashboard['annotations']['list']=[{'name':'First observed worker version','datasource':S,'enable':True,'hide':False,'iconColor':'#B877D9','rawQuery':True,'rawSql':"SELECT observed AS time, 'First observed ' || service_version AS text, instance AS tags FROM (SELECT min(claimed_at) AS observed,service_version,instance FROM support.attempts WHERE service_version IS NOT NULL GROUP BY service_version,instance) versions WHERE $__timeFilter(observed)"}]
for d,name in [(D,'mocknet.json'),(E,'stages.json'),(F,'operation.json')]:
 (OUT/name).write_text(json.dumps(d,indent=2)+'\n')
print('Generated 3 dashboards with',sum(len(d['panels']) for d in [D,E,F]),'panels')

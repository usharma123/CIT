#!/usr/bin/env python3
"""Grafana views built exclusively from the C reporting store."""
import json
import pathlib

ROOT=pathlib.Path(__file__).resolve().parents[1]
OUT=ROOT/'observability/approach-c/grafana'
DS={'type':'grafana-postgresql-datasource','uid':'mocknet-c-reporting'}
stage="stage ~ ${stage:sqlstring}"
operation="operation_id=${operation:sqlstring}"
search="(${search:sqlstring}='' OR operation_id=${search:sqlstring} OR business_id=${search:sqlstring})"
links=[{'type':'link','title':title,'url':'/d/'+uid,'includeVars':True,'keepTime':True} for title,uid in [('C overview','mocknet-c-overview'),('C queues','mocknet-c-queues'),('C investigation','mocknet-c-investigation')]]
links += [{'type':'link','title':'Compare '+name,'url':'http://localhost:3300/d/'+uid,'keepTime':True} for name,uid in [('A','mocknet-traces'),('B','mocknet-b-overview')]]
variables=[{'name':'stage','label':'Queue / stage','type':'custom','query':'INGESTION,MATCHING,NETTING,SETTLEMENT,DEAD_LETTER','includeAll':True,'allValue':"'.*'",'current':{'text':'All','value':'$__all'}},
 {'name':'search','label':'Find trade / operation','description':'Filters operation lists and the investigation selector. Overview charts remain stage-wide.','type':'textbox','query':'','current':{'text':'','value':''}}]

def dashboard(uid,title,investigation=False):
    v=list(variables[1:] if investigation else variables)
    if investigation:
        v.append({'name':'operation','label':'Operation','type':'query','datasource':DS,'query':f"SELECT coalesce(business_id,'unparsed') || ' | ' || operation_id AS __text, operation_id AS __value FROM c_operations WHERE {search} ORDER BY admitted_at DESC LIMIT 1000",'refresh':1})
    return {'uid':uid,'title':title,'schemaVersion':40,'version':1,'editable':False,'tags':['mocknet','Approach C','logs and journals'],
      'timezone':'browser','refresh':'10s','time':{'from':'now-15m','to':'now'},'links':links,'templating':{'list':v},'annotations':{'list':[]},
      'description':'Reconstructed evidence from component logs and committed MQ journals. No application spans or Java agent. Current-state tables are latest collected state; graphs follow the selected interval. A returned method is not proof of transaction commit. Missing evidence stays explicit.','panels':[]}

def panel(d,title,query,x,y,w=24,h=8,kind='table',unit='short',description=''):
    p={'id':len(d['panels'])+1,'title':title,'type':kind,'datasource':DS,'gridPos':{'x':x,'y':y,'w':w,'h':h},'description':description,
      'targets':[{'refId':'A','rawSql':query,'format':'time_series' if kind in ('timeseries','state-timeline') else 'table','editorMode':'code','rawQuery':True}],
      'fieldConfig':{'defaults':{'unit':unit,'color':{'mode':'palette-classic'},'custom':{'lineWidth':2,'fillOpacity':6,'spanNulls':False}},'overrides':[]},
      'options':{'showHeader':True,'cellHeight':'sm','footer':{'show':False}} if kind=='table' else {'legend':{'displayMode':'list','placement':'bottom'},'tooltip':{'mode':'multi'}}}
    d['panels'].append(p)
    return p

def drill(p,field='operation_id',precise=False):
    interval='from=${__data.fields.window_from}&to=${__data.fields.window_to}' if precise else '${__url_time_range}'
    p['fieldConfig']['overrides'].append({'matcher':{'id':'byName','options':field},'properties':[{'id':'links','value':[{'title':'Reconstruct operation','url':'/d/mocknet-c-investigation?'+interval+'&var-operation=${__value.raw}&var-search=${__value.raw}'}]}]})
    if precise:
        for name in ('window_from','window_to'):
            p['fieldConfig']['overrides'].append({'matcher':{'id':'byName','options':name},'properties':[{'id':'custom.hidden','value':True}]})
    for name,width in [('operation_id',300),('business_id',300),('unfinished',75),('failed_messages',75),('dead_letters',65)]:
        p['fieldConfig']['overrides'].append({'matcher':{'id':'byName','options':name},'properties':[{'id':'custom.width','value':width}]})

health="SELECT collected_at,round(collector_age_seconds::numeric,1) AS collector_age_seconds,pending_journal,oldest_pending_seconds,quarantined_lines,incomplete_calls,internal_log_sequence_gaps FROM c_evidence_health"
inventory=f"""SELECT stage,state,count(*) AS messages,
 round(max(CASE WHEN state='ready' THEN extract(epoch FROM now()-available_at) WHEN state='processing' THEN extract(epoch FROM now()-claimed_at) END)::numeric,2) AS oldest_seconds
 FROM c_queue WHERE {stage} GROUP BY stage,state ORDER BY stage,state"""
D=dashboard('mocknet-c-overview','Approach C | Service evidence overview')
p=panel(D,'Operations to investigate',f"""SELECT business_id,disposition,unfinished,failed_messages,dead_letters,last_event_at,operation_id,
 (extract(epoch FROM admitted_at)*1000-250)::bigint AS window_from,(extract(epoch FROM last_event_at)*1000+250)::bigint AS window_to
 FROM c_operations WHERE {search} AND (unfinished>0 OR failed_messages>0 OR disposition='rejected' OR $__timeFilter(admitted_at))
 ORDER BY (unfinished>0) DESC,last_event_at DESC LIMIT 100""",0,0,h=10,
 description='Latest collected queue disposition. Current unresolved work is included regardless of admission time. Queue work finished does not assert external settlement. Open the operation ID for evidence.');drill(p,precise=True)
panel(D,'Queue work waiting',f"SELECT at AS time,ready AS value,stage AS metric FROM queue_samples WHERE $__timeFilter(at) AND {stage} AND stage!='DEAD_LETTER' ORDER BY at",0,10,12,8,'timeseries')
panel(D,'Oldest ready message',f"SELECT at AS time,oldest_ready_seconds AS value,stage AS metric FROM queue_samples WHERE $__timeFilter(at) AND {stage} AND stage!='DEAD_LETTER' ORDER BY at",12,10,12,8,'timeseries','s')
panel(D,'Committed attempt outcomes',f"SELECT to_timestamp(floor(extract(epoch FROM finished_at)/10)*10) AS time,count(*)::double precision AS value,stage || ' ' || outcome AS metric FROM c_attempt WHERE $__timeFilter(finished_at) AND {stage} GROUP BY 1,3 ORDER BY 1",0,18,12,8,'timeseries',description='Attempt counts per 10-second bucket reconstructed from journaled dispositions.')
panel(D,'Failure reasons',f"SELECT stage,reason,outcome,count(*) AS attempts FROM c_attempt WHERE $__timeFilter(finished_at) AND {stage} AND reason IS NOT NULL GROUP BY stage,reason,outcome ORDER BY attempts DESC",12,18,12,8)
panel(D,'Slow components',f"SELECT component,count(*) AS calls,round(percentile_cont(.95) WITHIN GROUP(ORDER BY duration_ms)::numeric,2) AS p95_ms,count(*) FILTER(WHERE outcome='exception') AS exceptions FROM c_call WHERE $__timeFilter(started_at) AND {stage} AND evidence_state='paired' GROUP BY component ORDER BY p95_ms DESC LIMIT 12",0,26,24,8,
 description='Inclusive method time from paired logs. Nested calls overlap; do not add their durations. Exceptions may be recovered by callers.')
panel(D,'Component exceptions including failed queue claims',f"SELECT body->>'component' AS component,body->>'stage' AS stage,body->>'error_type' AS error_type,body->>'cause_type' AS cause_type,count(*) AS events,max(at) AS last_seen FROM evidence WHERE source='component_log' AND body->>'outcome'='exception' AND $__timeFilter(at) AND coalesce(body->>'stage','ADMISSION') ~ ${{stage:sqlstring}} GROUP BY 1,2,3,4 ORDER BY events DESC",0,34,24,8,
 description='Claim failures occur before an operation is assigned to a worker. They identify the queue and exception class but cannot be attributed to a particular waiting transaction.')

Q=dashboard('mocknet-c-queues','Approach C | Queue journal diagnostics')
panel(Q,'Queue inventory from the journal',inventory,0,0,24,8,description='Current state reconstructed from the latest committed journal record for each message. SETTLEMENT is an idle separate queue in this workload; instruction generation runs within NETTING.')
p=panel(Q,'Current waiting work and dead letters',f"SELECT business_id,stage,state,worker,retries,available_at,message_id,operation_id FROM c_queue WHERE {stage} AND {search} AND (state IN('ready','scheduled','processing') OR stage='DEAD_LETTER') ORDER BY created_at LIMIT 200",0,8,24,9);drill(p)
panel(Q,'Ready, scheduled and processing',f"SELECT at AS time,ready::double precision AS value,stage || ' ready' AS metric FROM queue_samples WHERE $__timeFilter(at) AND {stage} UNION ALL SELECT at,scheduled,stage || ' scheduled' FROM queue_samples WHERE $__timeFilter(at) AND {stage} UNION ALL SELECT at,processing,stage || ' processing' FROM queue_samples WHERE $__timeFilter(at) AND {stage} ORDER BY 1",0,17,12,8,'timeseries')
panel(Q,'Wait versus handler duration · p95',f"SELECT to_timestamp(floor(extract(epoch FROM finished_at)/30)*30) AS time,percentile_cont(.95) WITHIN GROUP(ORDER BY wait_seconds) AS value,stage || ' wait' AS metric FROM c_attempt WHERE $__timeFilter(finished_at) AND {stage} GROUP BY 1,3 UNION ALL SELECT to_timestamp(floor(extract(epoch FROM finished_at)/30)*30),percentile_cont(.95) WITHIN GROUP(ORDER BY duration_seconds),stage || ' handler' FROM c_attempt WHERE $__timeFilter(finished_at) AND {stage} GROUP BY 1,3 ORDER BY 1",12,17,12,8,'timeseries','s')
p=panel(Q,'Retry and failure history',f"SELECT started_at,stage,attempt,wait_seconds,duration_seconds,outcome,reason,worker,message_id,operation_id FROM c_attempt WHERE $__timeFilter(started_at) AND {stage} AND outcome IN('retried','failed','abandoned','rejected') ORDER BY started_at DESC LIMIT 150",0,25,24,9);drill(p)
panel(Q,'Evidence completeness',health,0,34,24,5,description='Collector age over 10 seconds means the reconstructed state may be stale. Incomplete calls can mean active work, crash loss or absent logging; inspect their start/end records. Quarantined lines are excluded, never silently accepted.')

I=dashboard('mocknet-c-investigation','Approach C | Reconstructed operation',True)
panel(I,'Operation and journal disposition',f"SELECT business_id,operation_id,admitted_at,last_event_at,disposition,messages,unfinished,dead_letters FROM c_operations WHERE {operation}",0,0,24,5)
p=panel(I,'Reconstructed operation waterfall',"SELECT * FROM c_waterfall(${operation:sqlstring})",0,5,24,18,'traces',
 description='Native Grafana waterfall reconstructed from component logs and committed MQ journals. Auto-fits the operation duration independently of the dashboard time window. Expand rows for source IDs, worker, retry reason and evidence warnings. Recorded call parents are preserved; worker/attempt grouping is explicitly inferred. Display trace/span IDs are deterministic reconstruction IDs, not OTel identifiers. Open/missing ends remain uncertain.')
p['options']={}
panel(I,'Calls, parent links and missing evidence',f"""SELECT component,stage,depth,started_at,finished_at,round(duration_ms::numeric,3) AS duration_ms,
 outcome,error_type,evidence_state,worker,message_id,published_message_id,call_id,parent_call_id
 FROM c_call WHERE {operation} ORDER BY coalesce(started_at,finished_at),depth""",0,17,24,11,
 description='Returned means the method returned. Use committed MQ dispositions below to establish queue success. Missing start/end records are retained as incomplete evidence.')
panel(I,'MQ attempt timeline',f"SELECT stage,message_id,attempt,started_at,finished_at,wait_seconds,duration_seconds,outcome,reason,worker FROM c_attempt WHERE {operation} ORDER BY started_at",0,28,24,9)
panel(I,'MQ state transitions',f"SELECT at,sequence,body->>'queue_name' AS queue,body->>'status' AS state,body->>'outcome' AS outcome,body->>'id' AS message_id,body->>'worker_name' AS worker,body->>'available_at' AS available_at FROM evidence WHERE {operation} AND source='mq_journal' AND kind='queue_messages' ORDER BY sequence",0,37,24,9,
 description='Only committed journal records enter this report. Journal sequence is a source ordering key, not a global distributed clock.')
panel(I,'Source records and collection delay',f"SELECT at,observed_at,round(extract(epoch FROM observed_at-at)::numeric,3) AS collection_delay_seconds,source,kind,event_id,body::text AS record FROM evidence WHERE {operation} ORDER BY at LIMIT 400",0,46,24,10)
panel(I,'Evidence completeness',health,0,56,24,5)
for item in I['panels'][2:]: item['gridPos']['y']+=6
for d in (D,Q,I):
    OUT.mkdir(parents=True,exist_ok=True)
    (OUT/(d['uid']+'.json')).write_text(json.dumps(d,indent=2)+'\n')
print('Generated C dashboards:',sum(len(d['panels']) for d in (D,Q,I)),'panels')

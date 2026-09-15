#!/usr/bin/env python3
"""Approach B: dashboards whose data queries use only Java-agent JVM runtime metrics."""
import json,pathlib
ROOT=pathlib.Path(__file__).resolve().parents[1]; OUT=ROOT/'observability/approach-b/grafana'
DS={'type':'prometheus','uid':'mocknet-b-metrics'}
F='service_name=~"$service",deployment_environment_name=~"$environment",service_version=~"$version",service_instance_id=~"$app_instance",otel_scope_name="io.opentelemetry.runtime-telemetry"'
V=[{'name':n,'label':label,'type':'query','datasource':DS,'query':f'label_values(jvm_memory_used_bytes{{service_name="mocknet-b"}}, {key})','refresh':1,'includeAll':True,'allValue':'.*','current':{'text':'All','value':'$__all'}} for n,label,key in [('environment','Environment','deployment_environment_name'),('service','Service','service_name'),('version','Version','service_version'),('app_instance','JVM instance','service_instance_id')]]
LINKS=[{'title':label,'type':'link','url':'/d/'+uid,'includeVars':True,'keepTime':True} for label,uid in [('B overview','mocknet-b-overview'),('B runtime diagnostics','mocknet-b-runtime'),('B incident investigation','mocknet-b-investigation')]]
LINKS.append({'title':'Compare Approach A','type':'link','url':'/d/mocknet-traces','keepTime':True})
LINKS.append({'title':'Compare Approach C','type':'link','url':'/d/mocknet-c-overview','keepTime':True})
def dashboard(uid,title):
 return {'uid':uid,'title':title,'schemaVersion':40,'version':1,'editable':False,'tags':['mocknet','Approach B','JVM only'],'timezone':'browser','refresh':'10s','time':{'from':'now-15m','to':'now'},'description':'Strict JVM-only evidence from the OpenTelemetry Java agent. No application metrics, SQL, logs, spans or span-derived metrics. Queue state, trade outcomes and individual request causes are not observable. Compare time trends before and during an incident. Missing data is not zero.','templating':{'list':V},'links':LINKS,'annotations':{'list':[]},'panels':[]}
def chart(d,title,queries,x,y,unit='short',desc='',h=8,w=12):
 p={'id':len(d['panels'])+1,'title':title,'type':'timeseries','datasource':DS,'gridPos':{'x':x,'y':y,'w':w,'h':h},'description':desc,'targets':[{'refId':chr(65+i),'expr':expr,'legendFormat':label,'range':True} for i,(label,expr) in enumerate(queries)],'fieldConfig':{'defaults':{'unit':unit,'min':0,'color':{'mode':'palette-classic'},'custom':{'lineWidth':2,'fillOpacity':6,'spanNulls':False}},'overrides':[]},'options':{'legend':{'displayMode':'list','placement':'bottom'},'tooltip':{'mode':'multi'}}}
 d['panels'].append(p);return p
cpu=[('JVM {{service_instance_id}}',f'jvm_cpu_recent_utilization_ratio{{{F}}}')]
heap=[(k+' {{service_instance_id}}',f'sum by(service_instance_id)(jvm_memory_{k}_bytes{{{F},jvm_memory_type="heap"}} > 0)') for k in ['used','committed','limit']]
threads=[('{{service_instance_id}} {{jvm_thread_state}}',f'sum by(service_instance_id,jvm_thread_state)(jvm_thread_count{{{F}}})')]
gctime=[('{{service_instance_id}} {{jvm_gc_name}}',f'sum by(service_instance_id,jvm_gc_name)(rate(jvm_gc_duration_seconds_sum{{{F}}}[$__rate_interval]))')]
aftergc=[('{{service_instance_id}} {{jvm_memory_pool_name}}',f'jvm_memory_used_after_last_gc_bytes{{{F},jvm_memory_type="heap"}}')]
allocation=[('{{service_instance_id}} {{arena}}',f'sum by(service_instance_id,arena)(rate(jvm_memory_allocation_bytes_sum{{{F}}}[$__rate_interval]))')]
gcp95=[('{{service_instance_id}} {{jvm_gc_name}}',f'histogram_quantile(0.95,sum by(le,service_instance_id,jvm_gc_name)(rate(jvm_gc_duration_seconds_bucket{{{F}}}[$__rate_interval])))')]
A=dashboard('mocknet-b-overview','Approach B | JVM service overview')
chart(A,'JVM CPU utilization',cpu,0,0,'percentunit','Process CPU reported by the JVM. Low CPU does not prove successful transaction processing.')
chart(A,'Heap used, committed and limit',heap,12,0,'bytes','Compare used memory with the configured heap limit. A rise alone is not a leak; inspect memory after GC.')
chart(A,'Time spent in garbage collection',gctime,0,8,'percentunit','GC duration seconds per elapsed second, grouped by collector. This is JVM GC activity, not request latency or exact application pause percentage.')
chart(A,'Threads by state',threads,12,8,desc='Platform thread states for the entire JVM. Waiting threads are often normal. These metrics do not identify queue consumers or the dependency on which a thread waits.')
chart(A,'Heap remaining after garbage collection',aftergc,0,16,'bytes','A sustained rising baseline can motivate memory investigation. This is not proof of a leak or an object-level allocation profile.')
chart(A,'Allocation rate',allocation,12,16,'Bps','JVM allocation volume reported by runtime telemetry, not transaction payload size. Experimental runtime metrics are pinned to this agent version.')
B=dashboard('mocknet-b-runtime','Approach B | JVM runtime diagnostics')
chart(B,'Memory used by pool', [('{{service_instance_id}} {{jvm_memory_pool_name}}',f'jvm_memory_used_bytes{{{F}}}')],0,0,'bytes')
chart(B,'Memory committed by pool',[('{{service_instance_id}} {{jvm_memory_pool_name}}',f'jvm_memory_committed_bytes{{{F}}}')],12,0,'bytes')
chart(B,'GC duration per event · p95',gcp95,0,8,'s','Histogram estimate of GC-event duration. No events means no quantile; it is not zero.')
chart(B,'GC events per second',[('{{service_instance_id}} {{jvm_gc_name}}',f'sum by(service_instance_id,jvm_gc_name)(rate(jvm_gc_duration_seconds_count{{{F}}}[$__rate_interval]))')],12,8,'ops')
chart(B,'Threads by state',threads,0,16)
chart(B,'JVM and host CPU',cpu+[('host as seen by {{service_instance_id}}',f'jvm_system_cpu_utilization_ratio{{{F}}}')],12,16,'percentunit','Host CPU includes other processes, including Approach A. It cannot attribute contention to a particular service.')
chart(B,'Direct and mapped buffer memory',[('{{service_instance_id}} {{jvm_buffer_pool_name}}',f'jvm_buffer_memory_used_bytes{{{F}}}')],0,24,'bytes')
chart(B,'Open file descriptors',[('{{service_instance_id}}',f'jvm_file_descriptor_count{{{F}}}')],12,24,desc='JVM descriptor count. It does not distinguish database sockets, files or telemetry connections.')
chart(B,'Loaded classes',[('{{service_instance_id}}',f'jvm_class_count{{{F}}}')],0,32)
chart(B,'Class loading and unloading',[(k+' {{service_instance_id}}',f'rate(jvm_class_{k}_total{{{F}}}[$__rate_interval])') for k in ['loaded','unloaded']],12,32,'ops')
C=dashboard('mocknet-b-investigation','Approach B | JVM incident investigation')
chart(C,'CPU during the incident',cpu,0,0,'percentunit','Zoom the incident interval and compare with the preceding baseline. JVM-only evidence cannot identify the trade or request consuming CPU.')
chart(C,'Heap pressure during the incident',heap,12,0,'bytes')
chart(C,'GC event duration · p95',gcp95,0,8,'s','Look for overlap between GC activity and reported service degradation. Temporal overlap does not prove the cause of an individual request failure.')
chart(C,'Thread state changes',threads,12,8,desc='Waiting, timed-waiting and runnable counts can reveal changes in execution. Queue identity, SQL statements and lock owners are unavailable.')
chart(C,'Heap retained after GC',aftergc,0,16,'bytes')
chart(C,'Allocation during the incident',allocation,12,16,'Bps')
for d in [A,B,C]:
 OUT.mkdir(parents=True,exist_ok=True);(OUT/(d['uid']+'.json')).write_text(json.dumps(d,indent=2)+'\n')
print('Generated Approach B: 3 JVM-only dashboards, 22 panels')

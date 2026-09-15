#!/usr/bin/env python3
"""Bounded L3 incident exercise against the owned local stack; saves every admission and assertion."""
import sys
sys.dont_write_bytecode = True
import http.client,concurrent.futures,datetime,importlib.util,json,os,pathlib,secrets,signal,subprocess,time,urllib.request,urllib.parse,urllib.error
ROOT=pathlib.Path(__file__).resolve().parents[1]; STATE=ROOT/'.bootstrap/observability'
def module(name,file):
 s=importlib.util.spec_from_file_location(name,ROOT/'script'/file);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
base=module('base','mocknet-grafana-demo.py');checks=module('checks','check-mocknet-dashboards.py')
RUN=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S')+'-'+secrets.token_hex(2)
TOKEN=dict(x.split('=',1) for x in (STATE/'grafana.env').read_text().splitlines())['MOCKNET_DEMO_TOKEN']
START=time.time();rows=[];evidence=[]
RESULT_FILE='l3-paired-results.json' if '--matched-only' in sys.argv else 'l3-results.json'
def save(): (STATE/RESULT_FILE).write_text(json.dumps({'runId':RUN,'startedAt':START,'updatedAt':time.time(),'admissions':rows,'checks':evidence},indent=2)+'\n')
def record(name,**data):
 evidence.append({'scenario':name,'at':time.time(),**data});save();print(json.dumps(evidence[-1]),flush=True)
def wait(check,desc,timeout=90):
 deadline=time.monotonic()+timeout
 while time.monotonic()<deadline:
  result=check()
  if result:return result
  time.sleep(1)
 raise AssertionError('Timed out: '+desc)
def prom(expr):
 return base.get('http://localhost:19090/api/v1/query?'+urllib.parse.urlencode({'query':expr}))['data']['result']
def maximum(expr):return max([float(r['value'][1]) for r in prom(expr)] or [0])
def control(path,token=TOKEN):
 req=urllib.request.Request(base.APP+'/api/demo/'+path,data=b'',headers={'X-Demo-Token':token})
 with urllib.request.urlopen(req,timeout=10) as r:return json.load(r)
def send(i,prefix='L3',name='load',trade_id=None,bank1=None,bank2=None):
 tid=trade_id or f'{prefix}-{RUN}-{name}-{i}';mid=f'MSG-{RUN}-{name}-{i}'
 xml=base.xml(tid,mid,bank1 or f'A-{RUN}-{name}-{i}',bank2 or f'B-{RUN}-{name}-{i}')
 start=time.monotonic()
 try:
  with urllib.request.urlopen(urllib.request.Request(base.APP+'/api/trades',data=xml.encode(),headers={'Content-Type':'application/xml'}),timeout=15) as r:
   result={'httpStatus':r.status,**json.load(r)}
 except urllib.error.HTTPError as e:result={'httpStatus':e.code,'error':e.read().decode()}
 result.update(scenario=name,tradeId=tid,admissionSeconds=time.monotonic()-start);rows.append(result);return result

def batch(n,name,prefix='L3',workers=24):
 t=time.monotonic()
 with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:result=list(pool.map(lambda i:send(i,prefix,name),range(n)))
 assert all(r['httpStatus']==202 for r in result),result
 record(name+'-admission',submitted=n,concurrency=workers,seconds=time.monotonic()-t)
 return result

def states(subset):
 ids=','.join("'"+r['operationId']+"'" for r in subset)
 return checks.sql('SELECT operation_id,outcome,recovered,trade_status FROM support.operations WHERE operation_id IN ('+ids+')')
def drained(subset):
 s=states(subset)
 return s if len(s)==len(subset) and all(r['outcome'] not in ('QUEUED','PROCESSING','PENDING') for r in s) else None

def compose(*args):
 subprocess.run(['docker','compose','--env-file',str(STATE/'grafana.env'),'-f',str(ROOT/'observability/compose.yaml'),*args],check=True,stdout=subprocess.DEVNULL)

def matched_load():
 def pair(i):
  a,b=f'PAIR-A-{RUN}-{i}',f'PAIR-B-{RUN}-{i}'
  return [send(i*2,name='matched-load',bank1=a,bank2=b),send(i*2+1,name='matched-load',bank1=b,bank2=a)]
 with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
  pairs=[row for result in pool.map(pair,range(60)) for row in result]
 assert all(r['httpStatus']==202 for r in pairs)
 def completed():
  current=states(pairs)
  return current if len(current)==120 and all(r['outcome']=='COMPLETED' for r in current) else None
 wait(completed,'60 matched pairs complete',120)
 record('matched-load-completed',pairs=60,operations=120,matchedTransactions=60)


def main():
 try:control('pause/INGESTION?seconds=1',token='invalid')
 except urllib.error.HTTPError as e: assert e.code==403
 else:raise AssertionError('Fault controls did not enforce authentication')
 record('fault-controls-authenticated',unauthorizedStatus=403)
 matched_load()
 load=batch(240,'concurrent-load');state=wait(lambda:drained(load),'240 messages drain')
 record('concurrent-load-drained',operations=len(state),outcomes={k:sum(r['outcome']==k for r in state) for k in set(r['outcome'] for r in state)})
 control('pause/INGESTION?seconds=90')
 stalled=batch(80,'worker-stall')
 try:
  depth=wait(lambda:maximum('mocknet_queue_messages{stage="INGESTION",state="ready"}')>=80,'backlog gauge')
  alert=wait(lambda:prom('ALERTS{alertname="QueueBacklogAging",alertstate="firing"}'),'backlog alert',45)
  assert not checks.sql("SELECT * FROM support.attempts WHERE business_id LIKE '%"+RUN+"-worker-stall-%'")
  record('worker-stall-visible-before-any-span',ready=maximum('mocknet_queue_messages{stage="INGESTION",state="ready"}'),oldestSeconds=maximum('mocknet_queue_oldest_seconds{stage="INGESTION",state="ready"}'),alert=alert)
 finally:control('pause/INGESTION?seconds=0')
 wait(lambda:drained(stalled),'stalled work recovery');record('worker-stall-recovered',operations=80)
 slow=batch(32,'slow-handlers','DEMO-SLOW');wait(lambda:drained(slow),'slow work drain')
 timing=checks.sql("SELECT max(processing_seconds) AS max_handler_seconds,max(wait_seconds) AS max_ready_wait_seconds FROM support.attempts WHERE business_id LIKE '%"+RUN+"-slow-handlers-%'")
 assert timing[0]['max_handler_seconds']>=.3 and timing[0]['max_ready_wait_seconds']>.3
 record('queue-wait-distinct-from-handler-delay',**timing[0])
 retry=batch(8,'retry-recovery','DEMO-RETRY-RECOVER');wait(lambda:drained(retry),'retry recovery')
 assert all(r['recovered'] and r['outcome']=='AWAITING_COUNTERPARTY' for r in states(retry))
 attempts=checks.sql("SELECT outcome,count(*) AS n FROM support.attempts WHERE stage='INGESTION' AND business_id LIKE '%"+RUN+"-retry-recovery-%' GROUP BY outcome")
 assert {r['outcome']:r['n'] for r in attempts}=={'retried':16,'completed':8}
 record('retries-are-not-terminal-failures',operations=8,attempts=attempts)
 exhaust=batch(4,'retry-exhaustion','DEMO-RETRY-EXHAUST');wait(lambda:drained(exhaust),'exhaustion')
 assert all(r['outcome']=='FAILED' for r in states(exhaust));record('202-followed-by-terminal-failure',operations=4,attemptsPerOperation=3)
 original=send(0,name='duplicate-original');wait(lambda:drained([original]),'original persists')
 duplicate=send(1,name='duplicate-replay',trade_id=original['tradeId']);wait(lambda:drained([duplicate]),'duplicate disposition')
 assert states([duplicate])[0]['outcome']=='FAILED'
 record('duplicate-business-id-is-not-a-second-trade',originalOperationId=original['operationId'],duplicateOperationId=duplicate['operationId'],duplicateOutcome='FAILED')
 control('pause/INGESTION?seconds=90');pressure=batch(24,'database-pressure')
 try:
  control('database-pressure?seconds=25');time.sleep(1);control('pause/INGESTION?seconds=0')
  wait(lambda:maximum('hikaricp_connections_pending')>0,'real Hikari waiters',20)
  alert=wait(lambda:prom('ALERTS{alertname="DatabasePoolSaturated",alertstate="firing"}'),'database saturation alert',20)
  record('real-database-pool-saturation',waiters=maximum('hikaricp_connections_pending'),active=maximum('hikaricp_connections_active'),alert=alert)
  wait(lambda:maximum('time()-mocknet_snapshot_timestamp')>15,'snapshot becomes stale',20)
  record('database-pressure-exposes-stale-queue-data',snapshotAgeSeconds=maximum('time()-mocknet_snapshot_timestamp'))
 finally:control('pause/INGESTION?seconds=0')
 wait(lambda:drained(pressure),'database automatic recovery',90);record('database-pressure-recovered',operations=24)
 compose('stop','tempo')
 try:
  outage=batch(12,'tempo-outage');wait(lambda:drained(outage),'business continues without tempo')
  wait(lambda:prom('ALERTS{alertname="TelemetryBackendUnavailable",job="tempo",alertstate="firing"}'),'Tempo unavailable alert',45)
  wait(lambda:maximum('otelcol_exporter_queue_size{exporter="otlp_grpc/tempo"}')>0,'persistent trace backlog',40)
  record('tempo-outage-business-continues',operations=12,bufferedBatches=maximum('otelcol_exporter_queue_size{exporter="otlp_grpc/tempo"}'))
 finally:compose('start','tempo')
 def traces_recovered():
  try:return base.get(base.TEMPO+'/api/traces/'+outage[0]['traceId'])
  except (urllib.error.URLError, http.client.HTTPException, OSError):return None
 wait(traces_recovered,'buffered trace appears after restart',90);record('tempo-recovered',traceId=outage[0]['traceId'])
 # Kill only the launcher-owned application after observing active claims, then restart it.
 restart=batch(80,'process-restart','DEMO-SLOW')
 wait(lambda:checks.sql("SELECT id FROM support.queue_messages WHERE status='PROCESSING' AND business_id LIKE '%"+RUN+"-process-restart-%' LIMIT 1"),'active claims before process crash')
 pid=int((STATE/'app.pid').read_text());cmd=subprocess.check_output(['ps','-p',str(pid),'-o','command='],text=True)
 assert str(ROOT/'mocknet/target/mocknet-mock-1.0.0-SNAPSHOT.jar') in cmd
 os.kill(pid,signal.SIGKILL)
 record('owned-backend-crashed-under-load',admitted=80)
 with (STATE/'l3-restart.log').open('w') as log:
  subprocess.run(['bash',str(ROOT/'script/mocknet-grafana.sh'),'start'],cwd=ROOT,env={**os.environ,'MOCKNET_VERSION':'l3-demo-v2'},stdout=log,stderr=subprocess.STDOUT,check=True)
 wait(lambda:drained(restart),'claims recovered after JVM crash',150)
 abandoned=checks.sql("SELECT count(*) AS n FROM support.attempts WHERE outcome='abandoned' AND business_id LIKE '%"+RUN+"-process-restart-%'")[0]['n']
 assert abandoned>0
 assert all(r['outcome']=='AWAITING_COUNTERPARTY' for r in states(restart))
 record('process-restart-recovered',operations=80,abandonedClaims=abandoned,version='l3-demo-v2')
 # A log query must contain actual operation and trace correlation.
 tid=restart[-1]['traceId']
 def logs():
  data=base.get('http://localhost:13100/loki/api/v1/query_range?'+urllib.parse.urlencode({'query':'{service_name="mocknet"} | trace_id="'+tid+'"','start':str(int(START*1e9)),'end':str(time.time_ns()),'limit':100}))
  return data['data']['result'] or None
 result=wait(logs,'trace-correlated structured logs',45)
 record('logs-correlate-to-traces',traceId=tid,streams=len(result),exampleLabels=result[0]['stream'])
 record('complete',accepted=len(rows),durationSeconds=time.time()-START)

if __name__=='__main__':
 try:
  if '--matched-only' in sys.argv: matched_load()
  else: main()
 except Exception as e:record('FAILED',error=str(e));raise

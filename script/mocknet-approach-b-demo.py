#!/usr/bin/env python3
"""B incident experiment. Ground truth is saved locally, never used by B dashboards."""
import sys
sys.dont_write_bytecode=True
import concurrent.futures,importlib.util,json,pathlib,subprocess,time,urllib.request,urllib.parse
ROOT=pathlib.Path(__file__).resolve().parents[1];STATE=ROOT/'.bootstrap/observability/approach-b'
def module(name,file):
 s=importlib.util.spec_from_file_location(name,ROOT/'script'/file);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
base=module('base','mocknet-grafana-demo.py');metrics=module('metrics','check-mocknet-approach-b.py')
APP='http://localhost:18091';RUN=str(int(time.time()));RESULT={'runId':RUN,'startedAt':time.time(),'phases':[],'admissions':[]}
TOKEN=dict(x.split('=',1) for x in (ROOT/'.bootstrap/observability/grafana.env').read_text().splitlines())['MOCKNET_DEMO_TOKEN']
def get(path):return metrics.get(APP+path)
def control(path):
 r=urllib.request.Request(APP+'/api/demo/'+path,data=b'',headers={'X-Demo-Token':TOKEN})
 with urllib.request.urlopen(r,timeout=30) as x:return json.load(x)
def record(name,**extra):
 row={'phase':name,'at':time.time(),'jvmMetrics':{}}
 for key,q in {'cpu':'jvm_cpu_recent_utilization_ratio','heap':'sum(jvm_memory_used_bytes{jvm_memory_type="heap"})','threads':'sum by(jvm_thread_state)(jvm_thread_count)','gc':'sum(jvm_gc_duration_seconds_sum)'}.items():row['jvmMetrics'][key]=metrics.prom(q)
 try: row['oracleOnly']=get('/api/status')
 except Exception as error: row['oracleOnly']={'unavailable':type(error).__name__}
 row.update(extra);RESULT['phases'].append(row)
 (STATE/'incident-results.json').write_text(json.dumps(RESULT,indent=2)+'\n')
 print(json.dumps({'phase':name,'at':row['at'],'ingestionReady':row['oracleOnly'].get('queues',{}).get('INGESTION',{}).get('NEW'),'deadLetters':row['oracleOnly'].get('queues',{}).get('DEAD_LETTER',{}).get('NEW'),**extra}),flush=True)
def send(i,prefix='B-LOAD',currency='GBP'):
 trade=f'{prefix}-{RUN}-{i}';xml=base.xml(trade,'MSG-'+trade,'A-'+trade,'B-'+trade,currency=currency)
 req=urllib.request.Request(APP+'/api/trades',data=xml.encode(),headers={'Content-Type':'application/xml'})
 with urllib.request.urlopen(req,timeout=20) as r:row={'httpStatus':r.status,**json.load(r)}
 assert row['httpStatus']==202 and row['traceId']=='0'*32,row
 RESULT['admissions'].append(row);return row
record('baseline')
try:
 control('pause/INGESTION?seconds=45')
 send(0,'B-PAUSED')
 time.sleep(25)
 record('consumer-stall')
 assert RESULT['phases'][-1]['oracleOnly']['queues']['INGESTION']['NEW']>=15
finally:control('pause/INGESTION?seconds=0')
time.sleep(8);record('consumer-recovered')
for j in range(3):
 with concurrent.futures.ThreadPoolExecutor(max_workers=24) as pool:list(pool.map(lambda i:send(i+j*120),range(120)))
 time.sleep(2)
time.sleep(5);record('360-concurrent-submissions',submitted=360)
for i in range(6):send(i,'DEMO-RETRY-EXHAUST-B')
for i in range(6):send(i,'B-REJECTED',currency='XXX')
time.sleep(8);record('business-failures-and-rejections')
try:
 control('database-pressure?seconds=20')
 time.sleep(8);record('database-connection-pressure')
finally:
 # The existing pressure control releases its own connections automatically.
 time.sleep(14)
record('database-recovered')
pid=int((STATE/'app.pid').read_text())
command=subprocess.check_output(['ps','-p',str(pid),'-o','command='],text=True)
assert str(STATE/'app.jar') in command,'Refusing to target an unowned JVM'
subprocess.run(['jcmd',str(pid),'GC.run'],check=True,capture_output=True,text=True)
time.sleep(8);record('explicit-gc')
RESULT['completedAt']=time.time();(STATE/'incident-results.json').write_text(json.dumps(RESULT,indent=2)+'\n')
print('Approach B experiment complete',flush=True)

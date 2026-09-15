#!/usr/bin/env python3
"""Exercise C queue diagnosis and collector recovery; keep evidence locally."""
import concurrent.futures,importlib.util,json,pathlib,subprocess,time,urllib.request,uuid
import psycopg
from psycopg.types.json import Jsonb
ROOT=pathlib.Path(__file__).resolve().parents[1];STATE=ROOT/'.bootstrap/observability/approach-c'
spec=importlib.util.spec_from_file_location('base',ROOT/'script/mocknet-grafana-demo.py');base=importlib.util.module_from_spec(spec);spec.loader.exec_module(base)
secret=dict(x.split('=',1) for x in (ROOT/'.bootstrap/observability/grafana.env').read_text().splitlines())
APP='http://localhost:18101';RUN=str(int(time.time()));RESULT={'run':RUN,'startedAt':time.time(),'phases':[],'checks':{},'admissions':[]}
def db(name='telemetry_c',user='c_reporter'):
 return psycopg.connect(host='127.0.0.1',port=15452,dbname=name,user=user,password=secret['MOCKNET_DB_PASSWORD' if user=='mocknet_c' else 'MOCKNET_C_COLLECTOR_PASSWORD'],autocommit=True)
def query(sql,params=()):
 with db() as c:return c.execute(sql,params).fetchall()
def save(): (STATE/'incident-results.json').write_text(json.dumps(RESULT,indent=2,default=str)+'\n')
def until(test,seconds=25):
 deadline=time.monotonic()+seconds
 while time.monotonic()<deadline:
  if test():return
  time.sleep(.5)
 raise AssertionError('Evidence did not converge')
def control(path):
 req=urllib.request.Request(APP+'/api/demo/'+path,data=b'',headers={'X-Demo-Token':secret['MOCKNET_DEMO_TOKEN']})
 with urllib.request.urlopen(req,timeout=30) as r:return json.load(r)
def reporter(action):subprocess.run([str(STATE/'venv/bin/python'),str(ROOT/'script/mocknet-c-reporter.py'),action],check=True,capture_output=True)
def send(i,prefix='C-LOAD',currency='GBP'):
 trade=f'{prefix}-{RUN}-{i}'
 body=base.xml(trade,'MSG-'+trade,'A-'+trade,'B-'+trade,currency=currency)
 req=urllib.request.Request(APP+'/api/trades',data=body.encode(),headers={'Content-Type':'application/xml'})
 with urllib.request.urlopen(req,timeout=20) as r: row={'httpStatus':r.status,**json.load(r)}
 assert row['httpStatus']==202 and row['traceId']=='0'*32
 RESULT['admissions'].append(row);return row
def record(name):
 row={'phase':name,'at':time.time(),'queues':query('select stage,state,count(*) from c_queue group by stage,state'),
      'health':query('select * from c_evidence_health'),'exceptions':query("select component,count(*) from c_call where outcome='exception' group by component")}
 RESULT['phases'].append(row);save();print(name,flush=True)
record('baseline')
try:
 control('pause/INGESTION?seconds=45');probe=send(0,'C-STALLED');time.sleep(20)
 record('consumer-stall')
 assert query("select state from c_queue where message_id=%s",(probe['queueMessageId'],))[0][0]=='ready'
 assert query("select extract(epoch FROM now()-available_at) from c_queue where message_id=%s",(probe['queueMessageId'],))[0][0]>=19
finally:control('pause/INGESTION?seconds=0')
until(lambda:query('select disposition from c_operations where operation_id=%s',(probe['operationId'],))[0][0]=='queue work finished')
record('consumer-recovered')
with concurrent.futures.ThreadPoolExecutor(max_workers=16) as pool:list(pool.map(send,range(120)))
for i in range(3):send(i,'DEMO-RETRY-EXHAUST-C')
for i in range(3):send(i,'C-REJECTED',currency='XXX')
time.sleep(10);record('load-and-business-failures')
try:
 reporter('stop');orphan=send(0,'C-COLLECTOR-OUTAGE');time.sleep(12)
 assert query('select collector_age_seconds from c_evidence_health')[0][0]>10
 assert not query('select 1 from c_operations where operation_id=%s',(orphan['operationId'],))
 record('collector-stopped')
finally:reporter('start')
until(lambda:bool(query('select 1 from c_operations where operation_id=%s',(orphan['operationId'],))))
record('collector-caught-up')
RESULT['checks']['collectorOutageRecovered']=True
# Replay acknowledgements after persistence. Existing event IDs must not duplicate.
before=query("select count(*) from evidence where source='mq_journal' and operation_id=%s",(probe['operationId'],))[0][0]
with db('mocknet_c','mocknet_c') as c:c.execute("update c_mq_journal set delivered_at=null where body->>'operation_id'=%s",(probe['operationId'],))
time.sleep(3)
assert query("select count(*) from evidence where source='mq_journal' and operation_id=%s",(probe['operationId'],))[0][0]==before
RESULT['checks']['journalReplayDeduplicated']=True
# A lower sequence may commit after a higher sequence. Both must be collected.
with db('mocknet_c','mocknet_c') as first,db('mocknet_c','mocknet_c') as second:
 first.execute('BEGIN')
 low=first.execute("insert into c_mq_journal(kind,entity_id,body) values('validation',0,%s) returning sequence",(Jsonb({'test':RUN}),)).fetchone()[0]
 high=second.execute("insert into c_mq_journal(kind,entity_id,body) values('validation',0,%s) returning sequence",(Jsonb({'test':RUN}),)).fetchone()[0]
 until(lambda:bool(query('select 1 from evidence where event_id=%s',('mq:'+str(high),))))
 first.execute('COMMIT')
 until(lambda:bool(query('select 1 from evidence where event_id=%s',('mq:'+str(low),))))
 first.execute('BEGIN')
 rolled=first.execute("insert into c_mq_journal(kind,entity_id,body) values('validation',0,%s) returning sequence",(Jsonb({'test':RUN}),)).fetchone()[0]
 first.execute('ROLLBACK');time.sleep(2)
 assert not query('select 1 from evidence where event_id=%s',('mq:'+str(rolled),))
RESULT['checks']['lateCommitCollected']=True;RESULT['checks']['rollbackAbsent']=True
# Partial lines, replay, a missing end, rotation and malformed input.
fixture=STATE/'logs'/f'components.validation-{RUN}.jsonl';call=str(uuid.uuid4())
start={'schema_version':1,'event_id':str(uuid.uuid4()),'source':'component_log','sequence':1,'boot_id':'validation-'+RUN,'at':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),'event':'start','call_id':call,'component':'CollectorValidation.synthetic','depth':0}
line=json.dumps(start)+'\n';fixture.write_text(line[:20]);time.sleep(2)
assert not query('select 1 from evidence where event_id=%s',('log:'+start['event_id'],))
with fixture.open('a') as f:f.write(line[20:])
until(lambda:bool(query("select 1 from c_call where call_id=%s and evidence_state='open or missing end'",(call,))))
end={**start,'event_id':str(uuid.uuid4()),'sequence':2,'event':'end','duration_ms':1,'outcome':'returned'}
with fixture.open('a') as f:f.write(line+json.dumps(end)+'\n'+'intentionally malformed validation line\n')
rotated=fixture.with_name(f'components.validation-{RUN}.rotated.jsonl');fixture.rename(rotated)
until(lambda:bool(query("select 1 from c_call where call_id=%s and evidence_state='paired'",(call,))))
assert query('select count(*) from evidence where entity_id=%s',(call,))[0][0]==2
assert query('select count(*) from quarantine')[0][0]>=1
RESULT['checks'].update(partialLineRetried=True,duplicateLogDeduplicated=True,missingEndExposed=True,rotationHandled=True,malformedLineQuarantined=True)
record('replay-and-integrity-checks')
try:
 control('database-pressure?seconds=15');time.sleep(6)
 record('database-pressure')
finally:time.sleep(12)
record('database-recovered');RESULT['completedAt']=time.time();save()
print(json.dumps(RESULT['checks']),flush=True)

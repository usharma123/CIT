#!/usr/bin/env python3
"""Controlled local application-cost comparison. Not a production capacity benchmark."""
import concurrent.futures,hashlib,importlib.util,json,os,pathlib,statistics,subprocess,time,urllib.request
import psycopg
from psycopg import sql
ROOT=pathlib.Path(__file__).resolve().parents[1];STATE=ROOT/'.bootstrap/observability/approach-c/benchmark'
STATE.mkdir(exist_ok=True)
secret=dict(x.split('=',1) for x in (ROOT/'.bootstrap/observability/grafana.env').read_text().splitlines())
spec=importlib.util.spec_from_file_location('base',ROOT/'script/mocknet-grafana-demo.py');base=importlib.util.module_from_spec(spec);spec.loader.exec_module(base)
JAR=ROOT/'.bootstrap/observability/approach-c/app.jar';AGENT=ROOT/'.bootstrap/observability/opentelemetry-javaagent-2.26.1.jar'
RUN=str(int(time.time()));RESULT={'run':RUN,'artifactSha256':hashlib.sha256(JAR.read_bytes()).hexdigest(),'heap':'128m initial / 512m maximum','workers':24,'pool':16,'warmupRequests':80,'measuredRequests':400,'concurrency':16,'runs':[]}
def connection(name):return psycopg.connect(host='127.0.0.1',port=15452,dbname=name,user='mocknet_c',password=secret['MOCKNET_DB_PASSWORD'],autocommit=True)
def get():
 with urllib.request.urlopen('http://127.0.0.1:18111/api/status',timeout=3) as r:return json.load(r)
def resources(pid):
 value=subprocess.check_output(['ps','-p',str(pid),'-o','rss=,time='],text=True).split()
 parts=value[1].split(':');cpu=sum(float(v)*60**i for i,v in enumerate(reversed(parts)))
 return int(value[0])/1024,cpu
def submit(i,label):
 pair=i//2;one=f'BANK-A-{label}-{pair}';two=f'BANK-B-{label}-{pair}'
 if i%2:one,two=two,one
 payload=base.xml(f'BENCH-{label}-{i}',f'MSG-{label}-{i}',one,two)
 req=urllib.request.Request('http://127.0.0.1:18111/api/trades',data=payload.encode(),headers={'Content-Type':'application/xml'})
 started=time.perf_counter()
 with urllib.request.urlopen(req,timeout=30) as response:assert response.status==202
 return (time.perf_counter()-started)*1000
def drain(db,timeout=60):
 deadline=time.monotonic()+timeout
 while time.monotonic()<deadline:
  if db.execute("select count(*) from queue_messages where status in('NEW','PROCESSING') and queue_name!='DEAD_LETTER'").fetchone()[0]==0:return
  time.sleep(.1)
 raise RuntimeError('Benchmark work did not drain')
for number,approach in enumerate('ABCCBA',1):
 name=f'bench_{RUN}_{number}';directory=STATE/name;directory.mkdir()
 with connection('mocknet_c') as admin:admin.execute(sql.SQL('CREATE DATABASE {}').format(sql.Identifier(name)))
 env={k:v for k,v in os.environ.items() if not k.startswith('OTEL_')}
 env.update(SPRING_DATASOURCE_PASSWORD=secret['MOCKNET_DB_PASSWORD'],MOCKNET_DEMO_TOKEN=secret['MOCKNET_DEMO_TOKEN'],MOCKNET_VERSION='benchmark',MOCKNET_INSTANCE='benchmark-'+approach.lower(),MOCKNET_COMPONENT_LOG_DIR=str(directory))
 args=['java','-Xms128m','-Xmx512m']
 if approach in 'AB':
  args.append('-javaagent:'+str(AGENT))
  env.update(OTEL_EXPORTER_OTLP_PROTOCOL='http/protobuf',OTEL_RESOURCE_ATTRIBUTES=f'service.version=benchmark,service.instance.id=benchmark-{approach.lower()},deployment.environment.name=benchmark')
 if approach=='A':
  env.update(OTEL_SERVICE_NAME='mocknet-benchmark-a',OTEL_TRACES_EXPORTER='otlp',OTEL_METRICS_EXPORTER='none',OTEL_LOGS_EXPORTER='otlp',OTEL_EXPORTER_OTLP_ENDPOINT='http://127.0.0.1:14318',OTEL_TRACES_SAMPLER='parentbased_always_on',OTEL_BSP_SCHEDULE_DELAY='1000',OTEL_BSP_MAX_QUEUE_SIZE='4096',OTEL_BSP_MAX_EXPORT_BATCH_SIZE='512',OTEL_INSTRUMENTATION_SPRING_DATA_ENABLED='false',OTEL_INSTRUMENTATION_HIBERNATE_ENABLED='false')
 elif approach=='B':
  env.update(OTEL_SERVICE_NAME='mocknet-b',OTEL_TRACES_EXPORTER='none',OTEL_LOGS_EXPORTER='none',OTEL_METRICS_EXPORTER='otlp',OTEL_TRACES_SAMPLER='always_off',OTEL_EXPORTER_OTLP_ENDPOINT='http://127.0.0.1:14328',OTEL_METRIC_EXPORT_INTERVAL='5000',OTEL_METRICS_EXEMPLAR_FILTER='always_off',OTEL_INSTRUMENTATION_COMMON_DEFAULT_ENABLED='false',OTEL_INSTRUMENTATION_RUNTIME_TELEMETRY_ENABLED='true',OTEL_INSTRUMENTATION_RUNTIME_TELEMETRY_EMIT_EXPERIMENTAL_METRICS='true',OTEL_INSTRUMENTATION_MICROMETER_ENABLED='false')
 args+=['-jar',str(JAR),'--server.address=127.0.0.1','--server.port=18111','--management.server.port=18112','--management.endpoints.web.exposure.include=health','--management.prometheus.metrics.export.enabled=false','--spring.profiles.active=observability-demo','--spring.h2.console.enabled=false',f'--spring.datasource.url=jdbc:postgresql://127.0.0.1:15452/{name}','--spring.datasource.driver-class-name=org.postgresql.Driver','--spring.datasource.username=mocknet_c','--mocknet.tracing.enabled='+str(approach=='A').lower(),'--mocknet.component-journal.enabled='+str(approach=='C').lower(),'--logging.level.root=WARN']
 if approach=='C':args+=['--logging.config='+str(ROOT/'observability/approach-c/logback.xml')]
 else:args+=['--logging.level.com.cit.mocknet.observability=INFO' if approach=='A' else '--logging.level.com.cit.mocknet.observability=WARN']
 process=None
 try:
  with (directory/'app.log').open('wb') as log:process=subprocess.Popen(args,env=env,stdin=subprocess.DEVNULL,stdout=log,stderr=log,start_new_session=True)
  for _ in range(90):
   if process.poll() is not None:raise RuntimeError('Benchmark JVM exited')
   try:get();break
   except Exception:time.sleep(1)
  else:raise RuntimeError('Benchmark JVM not ready')
  with connection(name) as db:
   if approach=='C':db.execute((ROOT/'observability/approach-c/journal.sql').read_text())
   with concurrent.futures.ThreadPoolExecutor(max_workers=16) as pool:list(pool.map(lambda i:submit(i,name+'-warm'),range(80)))
   drain(db);time.sleep(2)
   start_rss,start_cpu=resources(process.pid);peak=start_rss;started=time.perf_counter()
   with concurrent.futures.ThreadPoolExecutor(max_workers=16) as pool:
    futures=[pool.submit(submit,i,name+'-run') for i in range(400)]
    while not all(f.done() for f in futures):
     peak=max(peak,resources(process.pid)[0]);time.sleep(.1)
    latencies=[f.result() for f in futures]
   admission_elapsed=time.perf_counter()-started
   drain(db);elapsed=time.perf_counter()-started
   end_rss,end_cpu=resources(process.pid);peak=max(peak,end_rss)
   row={'order':number,'approach':approach,'database':name,'httpP50Ms':statistics.median(latencies),'httpP95Ms':sorted(latencies)[379],
        'admissionSeconds':admission_elapsed,'drainSeconds':elapsed,'jvmCpuSeconds':end_cpu-start_cpu,'peakSampledRssMiB':peak,
        'attemptOutcomes':db.execute('select outcome,count(*) from processing_attempts group by outcome').fetchall(),
        'tradeOutcomes':db.execute('select status,count(*) from trades group by status').fetchall()}
   if approach=='C':
    row['journalRowsIncludingWarmup']=db.execute('select count(*) from c_mq_journal').fetchone()[0]
    row['journalBytesIncludingWarmup']=db.execute("select pg_total_relation_size('c_mq_journal')").fetchone()[0]
    row['componentLogBytesIncludingWarmup']=sum(p.stat().st_size for p in directory.glob('components*.jsonl'))
   RESULT['runs'].append(row);(STATE/'results.json').write_text(json.dumps(RESULT,indent=2)+'\n');print(json.dumps(row),flush=True)
 finally:
  if process is not None and process.poll() is None:
   process.terminate()
   try:process.wait(timeout=30)
   except subprocess.TimeoutExpired:process.kill();process.wait()
RESULT['completedAt']=time.time();(STATE/'results.json').write_text(json.dumps(RESULT,indent=2)+'\n')

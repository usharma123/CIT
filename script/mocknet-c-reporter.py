#!/usr/bin/env python3
"""Pool committed MQ journal events and structured component logs. No OTLP or spans."""
import argparse
import datetime
import hashlib
import json
import math
import os
import pathlib
import signal
import subprocess
import sys
import time
import threading
import uuid
import urllib.request
import psycopg
from psycopg.types.json import Jsonb

ROOT = pathlib.Path(__file__).resolve().parents[1]
STATE = ROOT / '.bootstrap/observability/approach-c'
SCRIPT = pathlib.Path(__file__).resolve()
RUNNING = True
BACKEND_CONFIG = json.loads((ROOT / 'observability/approach-c/backend/settings.json').read_text())
SOURCE_STOP = threading.Event()

def source_loop():
    # A slow/unavailable REST source must never block the committed journal consumer.
    while not SOURCE_STOP.is_set():
        try:
            with connect('telemetry_c','c_reporter') as report:
                report.autocommit=True
                collect_sources(report)
        except (OSError,ValueError,KeyError,psycopg.Error) as error:
            print(json.dumps({'source_collection_error':type(error).__name__}),flush=True)
        SOURCE_STOP.wait(BACKEND_CONFIG['source_poll_seconds'])

def collect_sources(report):
    """Isolate API outages from durable journal/log collection."""
    secret = dict(x.split('=',1) for x in (ROOT / '.bootstrap/observability/grafana.env').read_text().splitlines())
    request = urllib.request.Request('http://127.0.0.1:18103/api/v1/sources',
        headers={'Authorization':'Bearer '+secret['MOCKNET_C_API_READER_TOKEN']})
    try:
        with urllib.request.urlopen(request,timeout=15) as response:
            snapshots = json.load(response)['sources']
    except (OSError,ValueError,KeyError) as error:
        print(json.dumps({'source_api_error':type(error).__name__}),flush=True)
        return
    with report.transaction():
        for sample in snapshots:
            report.execute("""INSERT INTO source_snapshots
                (source_id,label,provenance,status,attempted_at,sampled_at,last_success_at,rows,error_code,stale_after_seconds)
                VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s) ON CONFLICT(source_id) DO UPDATE SET
                label=excluded.label,provenance=excluded.provenance,status=excluded.status,
                attempted_at=excluded.attempted_at,sampled_at=CASE WHEN excluded.status IN('ok','stale') THEN excluded.sampled_at ELSE source_snapshots.sampled_at END,
                last_success_at=coalesce(excluded.last_success_at,source_snapshots.last_success_at),
                rows=CASE WHEN excluded.status IN('ok','stale') THEN excluded.rows ELSE source_snapshots.rows END,error_code=excluded.error_code,
                stale_after_seconds=excluded.stale_after_seconds""",
                (sample['source_id'],sample['label'],sample['provenance'],sample['status'],sample['attempted_at'],sample['sampled_at'],
                 sample['attempted_at'] if sample['status'] in ('ok','stale') else None,Jsonb(sample['rows']),sample['error_code'],BACKEND_CONFIG['source_stale_seconds']))
    # Emulate a separate ODS export with its own observation clock and refresh delay.
    database=next((s for s in snapshots if s['source_id']=='database' and s['status']=='ok'),None)
    path=STATE/'ods/snapshot.json'
    if database and (not path.exists() or time.time()-path.stat().st_mtime>=BACKEND_CONFIG['ods_refresh_seconds']):
        path.parent.mkdir(exist_ok=True)
        temporary=path.with_suffix('.tmp')
        temporary.write_text(json.dumps({'schema_version':1,'provenance':'local-emulation',
            'sampled_at':database['sampled_at'],'rows':database['rows']})+'\n')
        temporary.replace(path)

def connect(database, user):
    secret = dict(x.split('=', 1) for x in (ROOT / '.bootstrap/observability/grafana.env').read_text().splitlines())
    return psycopg.connect(host='127.0.0.1', port=15452, dbname=database, user=user,
                          password=secret['MOCKNET_C_COLLECTOR_PASSWORD'], connect_timeout=5)

def insert(db, event_id, source, at, sequence, kind, entity, operation, body):
    db.execute('INSERT INTO evidence(event_id,source,at,sequence,kind,entity_id,operation_id,body) VALUES(%s,%s,%s,%s,%s,%s,%s,%s) ON CONFLICT(event_id) DO NOTHING',
               (event_id,source,at,sequence,kind,str(entity),operation,Jsonb(body)))

def validate_component(body):
    if not isinstance(body, dict) or body.get('schema_version') != 1 or body.get('source') != 'component_log' or body.get('event') not in ('start','end','failure'):
        raise ValueError('unsupported component event')
    for key in ('event_id','call_id'):
        if not isinstance(body.get(key),str): raise ValueError('invalid identifier type')
        uuid.UUID(body[key])
    if body.get('parent_call_id') is not None:
        if not isinstance(body['parent_call_id'],str): raise ValueError('invalid parent ID')
        uuid.UUID(body['parent_call_id'])
    if type(body.get('depth')) is not int or not 0 <= body['depth'] <= 1024:
        raise ValueError('invalid call depth')
    if type(body['sequence']) is not int or not 0 < body['sequence'] < 2**63:
        raise ValueError('invalid sequence')
    for key in ('at','started_at'):
        if body.get(key) is not None:
            if not isinstance(body[key],str): raise ValueError('invalid timestamp type')
            stamp=datetime.datetime.fromisoformat(body[key].replace('Z','+00:00'))
            if stamp.tzinfo is None: raise ValueError('timestamp needs timezone')
        elif key=='at': raise ValueError('missing timestamp')
    duration=body.get('duration_ms',0)
    if not isinstance(duration,(int,float)) or not math.isfinite(duration) or duration<0:
        raise ValueError('invalid duration')
    for key in ('component','boot_id'):
        if not isinstance(body.get(key),str) or not 0<len(body[key])<=256: raise ValueError('invalid identity')
    if body.get('operation_id') is not None and (not isinstance(body['operation_id'],str) or len(body['operation_id'])>256):
        raise ValueError('invalid operation ID')

def collect_journal(source, report):
    # Pending acknowledgements avoid losing a lower sequence committed after a higher one.
    with source.transaction():
        rows = source.execute('SELECT sequence,at,kind,entity_id,body FROM c_mq_journal WHERE delivered_at IS NULL ORDER BY sequence LIMIT 500 FOR UPDATE SKIP LOCKED').fetchall()
        with report.transaction():
            for seq, at, kind, entity, body in rows:
                insert(report, 'mq:'+str(seq), 'mq_journal', at, seq, kind, entity, body.get('operation_id'), body)
        # Commit reporting first. A crash here causes harmless replay, not lost evidence.
        if rows:
            source.execute('UPDATE c_mq_journal SET delivered_at=clock_timestamp() WHERE sequence=ANY(%s)', ([r[0] for r in rows],))
    return len(rows)

def collect_logs(report):
    count = 0
    for path in sorted((STATE / 'logs').glob('components*.jsonl')):
        try:
            with path.open('rb') as stream, report.transaction():
                st = os.fstat(stream.fileno())
                birth = getattr(st, 'st_birthtime', 0)
                identity = f'{st.st_dev}:{st.st_ino}:{birth}'
                row = report.execute('SELECT position FROM log_checkpoints WHERE file_id=%s', (identity,)).fetchone()
                position = row[0] if row else 0
                if st.st_size < position:
                    report.execute('INSERT INTO quarantine(file_id,position,error) VALUES(%s,%s,%s) ON CONFLICT DO NOTHING', (identity, position, 'file truncated after checkpoint'))
                    position = 0
                stream.seek(position)
                for _ in range(1000):
                    begin = stream.tell()
                    raw = stream.readline(65537)
                    if not raw: break
                    if not raw.endswith(b'\n') and len(raw) <= 65536:
                        break  # Partial final record: retain the earlier offset until complete.
                    try:
                        if len(raw) > 65536:
                            while raw and not raw.endswith(b'\n'): raw = stream.readline(65537)
                            raise ValueError('oversized line')
                        body = json.loads(raw)
                        validate_component(body)
                        insert(report, 'log:'+body['event_id'], 'component_log', body['at'], body['sequence'], body['event'], body['call_id'], body.get('operation_id'), body)
                        count += 1
                    except (ValueError, KeyError, TypeError, UnicodeError) as error:
                        report.execute('INSERT INTO quarantine(file_id,position,error) VALUES(%s,%s,%s) ON CONFLICT DO NOTHING',
                                       (identity, begin, type(error).__name__+': sha256='+hashlib.sha256(raw).hexdigest()))
                    position = stream.tell()
                report.execute('INSERT INTO log_checkpoints(file_id,position,path) VALUES(%s,%s,%s) ON CONFLICT(file_id) DO UPDATE SET position=excluded.position,path=excluded.path', (identity, position, str(path)))
        except FileNotFoundError:
            continue  # A concurrent rename will be discovered on the next scan.
    return count

def maintain(source, report):
    # Preserve latest state even for old unresolved messages. Detailed history expires.
    with report.transaction():
        report.execute("""DELETE FROM evidence e WHERE observed_at<now()-interval '72 hours'
          AND (source='component_log' OR (source='mq_journal' AND EXISTS(
            SELECT 1 FROM evidence newer WHERE newer.source=e.source AND newer.kind=e.kind
              AND newer.entity_id=e.entity_id AND newer.sequence>e.sequence)))""")
        report.execute("DELETE FROM queue_samples WHERE at<now()-interval '72 hours'")
        report.execute("DELETE FROM tool_runs WHERE requested_at<now()-interval '72 hours'")
        report.execute("UPDATE tool_runs SET status='interrupted',error_code='completion not recorded' WHERE status='running' AND requested_at<now()-interval '2 minutes'")
    source.execute("DELETE FROM c_mq_journal WHERE delivered_at<now()-interval '72 hours'")

def stop_signal(*_):
    global RUNNING
    RUNNING = False
    SOURCE_STOP.set()

def run():
    signal.signal(signal.SIGTERM, stop_signal)
    signal.signal(signal.SIGINT, stop_signal)
    (STATE / 'reporter.pid').write_text(str(os.getpid()))
    source = report = None
    last_sample = 0
    last_maintenance = time.monotonic()
    sources_thread=threading.Thread(target=source_loop,daemon=True,name='c-source-collector')
    sources_thread.start()
    try:
        while RUNNING:
            try:
                if source is None:
                    source = connect('mocknet_c','c_journal_reader')
                    report = connect('telemetry_c','c_reporter')
                    source.autocommit = report.autocommit = True
                journals = collect_journal(source, report)
                logs = collect_logs(report)
                if time.monotonic()-last_maintenance >= 3600:
                    maintain(source, report)
                    last_maintenance = time.monotonic()
                pending, oldest = source.execute('SELECT count(*),coalesce(extract(epoch FROM now()-min(at)),0) FROM c_mq_journal WHERE delivered_at IS NULL').fetchone()
                if time.monotonic()-last_sample >= 5:
                    report.execute("""INSERT INTO queue_samples
                      SELECT now(),s.stage,count(*) FILTER(WHERE q.state='ready'),count(*) FILTER(WHERE q.state='scheduled'),
                        count(*) FILTER(WHERE q.state='processing'),
                        coalesce(max(extract(epoch FROM now()-q.available_at)) FILTER(WHERE q.state='ready'),0),
                        coalesce(max(extract(epoch FROM now()-q.claimed_at)) FILTER(WHERE q.state='processing'),0)
                      FROM unnest(ARRAY['INGESTION','MATCHING','NETTING','SETTLEMENT','DEAD_LETTER']) AS s(stage)
                      LEFT JOIN c_queue q ON q.stage=s.stage GROUP BY s.stage""")
                    last_sample=time.monotonic()
                report.execute('INSERT INTO collector_status(id,at,pending_journal,oldest_pending_seconds,note) VALUES(1,now(),%s,%s,%s) ON CONFLICT(id) DO UPDATE SET at=excluded.at,pending_journal=excluded.pending_journal,oldest_pending_seconds=excluded.oldest_pending_seconds,note=excluded.note',
                               (pending,float(oldest),'journal acknowledgements and file checkpoints committed'))
                (STATE / 'reporter-status.json').write_text(json.dumps({'at':time.time(),'journalBatch':journals,'logBatch':logs,'pendingJournal':pending}))
            except Exception as error:
                print(json.dumps({'at':time.time(),'collector_error':type(error).__name__}),flush=True)
                for db in (source,report):
                    if db is not None: db.close()
                source = report = None
            time.sleep(1)
    finally:
        SOURCE_STOP.set()
        for db in (source,report):
            if db is not None: db.close()
        (STATE / 'reporter.pid').unlink(missing_ok=True)

def owned_pid():
    path = STATE / 'reporter.pid'
    if not path.exists(): return None
    pid = int(path.read_text())
    result = subprocess.run(['ps','-p',str(pid),'-o','command='],capture_output=True,text=True)
    return pid if str(SCRIPT)+' run' in result.stdout else None

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['start','stop','run','status'])
    args=parser.parse_args()
    if args.command=='run': return run()
    pid=owned_pid()
    if args.command=='start' and not pid:
        with (STATE/'reporter.log').open('ab') as output:
            subprocess.Popen([sys.executable,str(SCRIPT),'run'],stdin=subprocess.DEVNULL,stdout=output,stderr=output,start_new_session=True)
        for _ in range(50):
            if owned_pid(): break
            time.sleep(.1)
        if not owned_pid(): raise RuntimeError('Reporter failed to start')
    elif args.command=='stop' and pid:
        os.kill(pid, signal.SIGTERM)
        for _ in range(100):
            if not owned_pid(): break
            time.sleep(.1)
        if owned_pid(): raise RuntimeError('Reporter did not stop')
    print(json.dumps({'pid':owned_pid(),'state':json.loads((STATE/'reporter-status.json').read_text()) if (STATE/'reporter-status.json').exists() else None}))

if __name__=='__main__': main()

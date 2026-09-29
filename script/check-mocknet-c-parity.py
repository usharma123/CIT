#!/usr/bin/env python3
"""Check C Drilldown backends, exact span counts and immutable export replay."""
import base64
import importlib.util
import json
import pathlib
import sys
import time
import urllib.parse
import urllib.request
from unittest.mock import Mock

ROOT=pathlib.Path(__file__).resolve().parents[1]
STATE=ROOT/'.bootstrap/observability/approach-c'
sys.path.insert(0,str(ROOT/'observability/approach-c/telemetry'))
import bridge
spec=importlib.util.spec_from_file_location('reporter',ROOT/'script/mocknet-c-reporter.py')
reporter=importlib.util.module_from_spec(spec);spec.loader.exec_module(reporter)


def get(url):
    with urllib.request.urlopen(urllib.request.Request(url,headers={'Accept':'application/json'}),timeout=30) as response:return json.load(response)


def metric(query,start,end):
    return get('http://localhost:3202/api/metrics/query_range?'+urllib.parse.urlencode({'q':query,'start':start,'end':end,'step':'15s'}))


def main():
    checks=[]
    with reporter.connect('telemetry_c','c_reporter') as db:
        row=db.execute("""SELECT e.operation_id,e.trace_id,e.span_ids FROM c_trace_exports e
          JOIN c_operations o USING(operation_id) WHERE e.state='exported'
          AND e.sent_at<now()-interval '30 seconds' AND o.admitted_at>now()-interval '5 minutes'
          ORDER BY e.sent_at DESC LIMIT 1""").fetchone()
        assert row,'Wait for a recent exported operation'
        operation,trace,expected=row
        data=get('http://localhost:3202/api/traces/'+trace)
        batches=data['batches']
        services={a['value']['stringValue'] for b in batches for a in b['resource']['attributes'] if a['key']=='service.name'}
        spans=[s for b in batches for scope in b.get('scopeSpans',[]) for s in scope['spans']]
        ids=[base64.b64decode(s['spanId']).hex() for s in spans]
        assert len(ids)==len(set(ids)) and set(ids)==set(expected)
        assert services=={'mocknet'}
        assert sum(not s.get('parentSpanId') for s in spans)==1
        assert all(not s.get('parentSpanId') or base64.b64decode(s['parentSpanId']).hex() in ids for s in spans)
        assert not any(a['key'].startswith('db.') for s in spans for a in s.get('attributes',[]))
        checks.append('Tempo preserves every exported span once, valid parents, one deployed service, no SQL-call spans')
        end=int(time.time());start=end-600
        counts={}
        for name,condition,expected_count in [('root','nestedSetParent<0',1),('all','true',len(expected))]:
            q='{ span.operation_id = "'+operation+'" && '+condition+' } | count_over_time()'
            result=metric(q,start,end)
            count=sum(float(s.get('value',0)) for series in result.get('series',[]) for s in series.get('samples',[]))
            assert count==expected_count,(name,count,expected_count)
            counts[name]=count
        checks.append('TraceQL counts one operation root and exactly the expected observation spans')
        for query in ['{nestedSetParent<0} | rate() by(resource.service.name)',
                      '{true} | rate()', '{nestedSetParent<0 && status=error} | rate()',
                      '{nestedSetParent<0} | histogram_over_time(duration)',
                      '{nestedSetParent<0} | quantile_over_time(duration,0.9)']:
            data=metric(query,start,end)
            assert data.get('series'),query
        checks.append('Root/all rates, errors, duration histogram and p90 query successfully')
        q='{ span.operation_id = "'+operation+'" }'
        search=get('http://localhost:3202/api/search?'+urllib.parse.urlencode({'q':q,'start':start,'end':end}))
        assert any(t['traceID'].zfill(32)==trace for t in search.get('traces',[]))
        checks.append('Time-bounded TraceQL search returns the chosen operation')
        logs=get('http://localhost:13102/loki/api/v1/query_range?'+urllib.parse.urlencode({
            'query':'{service_name="mocknet"} | trace_id="'+trace+'"','start':str(start*10**9),'end':str(end*10**9),'limit':1000}))
        entries=[(stamp,line) for stream in logs['data']['result'] for stamp,line,*_ in stream['values']]
        assert entries and len(entries)==len(set(entries))
        checks.append('Exact trace-to-log filter returns unique correlated records')
        # Fixtures are uncommitted and rolled back. No network writes occur here.
        marker='parity-ledger-fixture'
        db.execute("INSERT INTO c_trace_exports(operation_id,trace_id,source_version,state,payload,span_ids) VALUES(%s,%s,1,'sending','{}','[]')",(marker,'f'*32))
        bridge.recover(db)
        assert db.execute('SELECT state FROM c_trace_exports WHERE operation_id=%s',(marker,)).fetchone()[0]=='uncertain'
        # Completed records never become pending during recovery.
        assert db.execute('SELECT state FROM c_trace_exports WHERE operation_id=%s',(operation,)).fetchone()[0]=='exported'
        db.rollback()
        # A new late record flags the frozen trace, while replay of its event ID
        # neither increments the source version twice nor adds another log export.
        version = db.execute('SELECT version FROM c_trace_activity WHERE operation_id=%s',(operation,)).fetchone()[0]
        statement = """INSERT INTO evidence(event_id,source,at,kind,entity_id,operation_id,body)
          VALUES(%s,'application_log',now(),'INFO',%s,%s,'{}') ON CONFLICT DO NOTHING"""
        for _ in range(2): db.execute(statement,(marker,marker,operation))
        assert db.execute('SELECT version FROM c_trace_activity WHERE operation_id=%s',(operation,)).fetchone()[0] == version+1
        assert db.execute('SELECT count(*) FROM c_log_exports WHERE event_id=%s',(marker,)).fetchone()[0] == 1
        assert db.execute('SELECT export_state FROM c_operation_trace WHERE operation_id=%s',(operation,)).fetchone()[0] == 'new evidence after snapshot'
        assert db.execute('SELECT state FROM c_trace_exports WHERE operation_id=%s',(operation,)).fetchone()[0] == 'exported'
        db.rollback()
        checks.append('Late evidence is flagged without resending the trace; duplicate event replay changes neither count nor version; fixtures rolled back')
        checks.append('Crash recovery quarantines interrupted delivery and preserves accepted delivery; fixture rolled back')
        # Run actual reporting retention inside an outer transaction; source pruning
        # is a mock so this check cannot change the application journal.
        for suffix,source,state in [('pending','application_log','pending'),('expired','application_log','exported'),('latest','mq_journal','exported')]:
            event=marker+'-'+suffix
            db.execute("""INSERT INTO evidence(event_id,source,at,observed_at,kind,entity_id,body)
              VALUES(%s,%s,now()-interval '80 hours',now()-interval '80 hours','parity',%s,'{}')""",(event,source,event))
            db.execute("UPDATE c_log_exports SET state=%s,sent_at=now()-interval '80 hours' WHERE event_id=%s",(state,event))
        reporter.maintain(Mock(),db)
        retained={r[0] for r in db.execute("SELECT event_id FROM evidence WHERE event_id LIKE %s",(marker+'-%',))}
        ledger={r[0] for r in db.execute("SELECT event_id FROM c_log_exports WHERE event_id LIKE %s",(marker+'-%',))}
        assert retained==ledger=={marker+'-pending',marker+'-latest'},(retained,ledger)
        db.rollback()
        checks.append('Retention preserves pending evidence and deduplication for retained journal records; expired delivered log and ledger removed together; fixtures rolled back')
    for query in ['up{job="mocknet"}', 'jvm_memory_used_bytes{area="heap"}', 'process_cpu_usage', 'hikaricp_connections_active','mocknet_processing_seconds_count']:
        data=get('http://localhost:19092/api/v1/query?'+urllib.parse.urlencode({'query':query}))
        assert data['data']['result'],query
    checks.append('C-only Micrometer scrape provides JVM, CPU, database-pool and committed-attempt metrics')
    output={'at':time.time(),'operation':operation,'traceId':trace,'spanCount':len(spans),'traceqlCounts':counts,'correlatedLogs':len(entries),'checks':checks}
    (STATE/'parity-validation.json').write_text(json.dumps(output,indent=2)+'\n')
    print(json.dumps(output,indent=2))


if __name__=='__main__':main()

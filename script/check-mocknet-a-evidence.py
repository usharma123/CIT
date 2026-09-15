#!/usr/bin/env python3
"""Bounded live audit of trace identities, committed attempts and Grafana correlation.
Run with A's continuous feed stopped; submits six local demo operations.
"""
import base64
import collections
import importlib.util
import json
from pathlib import Path
import re
import time
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / '.bootstrap/observability/approach-a/noise-audit'
OUT.mkdir(parents=True, exist_ok=True)
spec = importlib.util.spec_from_file_location('demo', ROOT/'script/mocknet-grafana-demo.py')
demo = importlib.util.module_from_spec(spec)
spec.loader.exec_module(demo)

def metrics():
    body = urllib.request.urlopen('http://127.0.0.1:18082/actuator/prometheus', timeout=10).read().decode()
    result = {}
    for line in body.splitlines():
        if line.startswith('mocknet_processing_seconds_count{'):
            labels = dict(re.findall(r'(\w+)="([^"]*)"', line))
            result[(labels['stage'], labels['outcome'])] = float(line.rsplit(' ', 1)[1])
    return result

def hexid(value):
    return base64.b64decode(value).hex() if '=' in value else value.lower()

def ready(operation):
    rows = demo.sql("SELECT * FROM support.operations WHERE operation_id='" + operation + "'")
    return rows and rows[0]['outcome'] not in ('PENDING', 'QUEUED', 'PROCESSING')

before = metrics()
a, b = 'AUDIT-A-' + demo.RUN, 'AUDIT-B-' + demo.RUN
rows = [demo.submit('audit-first', bank1=a, bank2=b)]
demo.wait_for(lambda: ready(rows[0]['operationId']), 'first leg completes matching')
rows.append(demo.submit('audit-second', bank1=b, bank2=a))
rows.append(demo.submit('audit-retry', prefix='DEMO-RETRY-RECOVER'))
rows.append(demo.submit('audit-normal'))
rows.append(demo.submit('audit-rejected', currency='XXX'))
rows.append(demo.submit('audit-malformed', malformed=True))
ids = ','.join("'"+r['operationId']+"'" for r in rows)
demo.wait_for(lambda: all(ready(r['operationId']) for r in rows), 'all audit operations finish', 60)
attempts = demo.sql(f'SELECT * FROM support.attempts WHERE operation_id IN ({ids}) ORDER BY id')
expected = collections.Counter((r['stage'], r['outcome']) for r in attempts)
after = metrics()
actual = {key: after.get(key, 0)-before.get(key, 0) for key in set(before)|set(after)}
assert {k:v for k,v in actual.items() if v} == dict(expected), (actual, expected)
operations = demo.sql(f'SELECT * FROM support.operations WHERE operation_id IN ({ids})')
assert len(operations) == len(rows) == len({r['operation_id'] for r in operations})
assert len(attempts) == len({r['id'] for r in attempts})
assert len({(r['queue_message_id'], r['claimed_at']) for r in attempts}) == len(attempts)
summary = []
for operation in operations:
    trace_id = operation['trace_id']
    expected_spans = {r['span_id'] for r in attempts if r['trace_id'] == trace_id}
    def complete_trace():
        try:
            trace = demo.get(demo.TEMPO+'/api/traces/'+trace_id)
            spans = list(demo.spans(trace))
            consumers = {hexid(s['spanId']) for s in spans if s['name']=='QueueMessage.process'}
            return (trace, spans) if consumers == expected_spans else None
        except urllib.error.HTTPError:
            return None
    trace, spans = demo.wait_for(complete_trace, 'complete exported trace '+trace_id, 60)
    span_ids = [hexid(s['spanId']) for s in spans]
    assert len(span_ids) == len(set(span_ids))
    for span in spans:
        assert int(span['endTimeUnixNano']) >= int(span['startTimeUnixNano'])
        parent = hexid(span.get('parentSpanId', ''))
        assert not parent or parent == '0000000000000000' or parent in span_ids, (span['name'], parent)
        assert demo.attrs(span).get('component.kind') != 'repository'
        if span['name']=='TwoPhaseCommitCoordinator.executeTransaction':
            assert demo.attrs(span)['component.stage']=='NETTING'
    scopes = collections.Counter()
    for batch in trace.get('batches', trace.get('resourceSpans', [])):
        for scope in batch.get('scopeSpans', batch.get('instrumentationLibrarySpans', [])):
            scopes[scope.get('scope', {}).get('name', '')] += len(scope.get('spans', []))
    assert scopes['io.opentelemetry.jdbc'] > 0
    (OUT/(trace_id+'-after.json')).write_text(json.dumps(trace))
    summary.append({'operation':operation['operation_id'], 'business':operation['business_id'], 'outcome':operation['outcome'],
                    'trace':trace_id, 'spans':len(spans), 'scopes':dict(scopes), 'consumerSpans':len(expected_spans),
                    'duplicateSpanIds':0})

# Verify the native trace panel data frame, including its units/identities.
selected = next(r for r in summary if r['business']==rows[1]['tradeId'])
payload={'from':'now-1h','to':'now','queries':[{'refId':'A','datasource':{'uid':'mocknet-tempo','type':'tempo'},'queryType':'traceId','query':selected['trace'],'tableType':'traces'}]}
request=urllib.request.Request('http://localhost:3300/api/ds/query',data=json.dumps(payload).encode(),headers={'Content-Type':'application/json'})
result=json.load(urllib.request.urlopen(request,timeout=30))['results']['A']
assert not result.get('error'),result
frame=result['frames'][0]
frame_rows=[dict(zip([f['name'] for f in frame['schema']['fields']], row)) for row in zip(*frame['data']['values'])]
assert len(frame_rows)==selected['spans']
assert len({r['spanID'] for r in frame_rows})==len(frame_rows)
assert all(r['duration']>=0 and r['startTime']>1e12 for r in frame_rows)

# Test the saved LogQL with real exact and deliberately truncated IDs.
dashboard=json.loads((ROOT/'observability/grafana/dashboards/operation.json').read_text())
expr=next(p for p in dashboard['panels'] if p['type']=='logs')['targets'][0]['expr']
def logs(trace, business):
    query=expr.replace('$service','mocknet').replace('$environment','local-demo').replace('${trace_id:doublequote}',json.dumps(trace)).replace('${business_id:doublequote}',json.dumps(business))
    url='http://localhost:13100/loki/api/v1/query_range?'+urllib.parse.urlencode({'query':query,'start':str(int((time.time()-3600)*1e9)),'end':str(time.time_ns()),'limit':1000})
    data=demo.get(url)
    return data['data']['result']
log_checks={}
exact=demo.wait_for(lambda: logs(selected['trace'],rows[0]['operationId']), 'logs across related leg', 30)
assert all(s['stream'].get('trace_id')==selected['trace'] for s in exact)
log_checks['selected_trace_overrides_related_operation_filter']=True
assert not logs(selected['trace'][:-1],'')
log_checks['trace_prefix_does_not_match']=True
assert logs('',rows[2]['operationId'])
assert not logs('',rows[2]['operationId'][:-1])
log_checks['operation_exact_match_only']=True

# The related-operation panel union must include shared netting exactly once.
timeline=next(p for p in dashboard['panels'] if p['title'].startswith('Attempt timeline'))['targets'][0]['rawSql']
for trade in rows[:2]:
    query=timeline.replace('${business_id:sqlstring}',"'"+trade['operationId']+"'")
    values=demo.sql(query)
    expected_ids={r['id'] for r in attempts if r['operation_id'] in [t['operationId'] for t in rows[:2]]}
    assert {r['attempt_id'] for r in values}==expected_ids and len(values)==len(expected_ids)
result={'passed':True,'operations':summary,'committedAttempts':len(attempts),
        'metricDeltas':{stage+'/'+outcome:count for (stage,outcome),count in expected.items()},
        'metricCountsMatchDatabase':True,'nativeTraceFrameRows':len(frame_rows),'logChecks':log_checks,
        'relatedTimelineUnique':True}
(OUT/'after-summary.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result,indent=2))

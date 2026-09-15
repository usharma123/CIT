#!/usr/bin/env python3
"""Exercise the C waterfall projection with transaction-local evidence fixtures."""
import datetime as dt
import json
from pathlib import Path
import uuid
import psycopg
from psycopg.types.json import Jsonb

ROOT = Path(__file__).resolve().parents[1]
secret = dict(x.split('=', 1) for x in (ROOT / '.bootstrap/observability/grafana.env').read_text().splitlines())
operation = str(uuid.uuid4())
base = dt.datetime.now(dt.timezone.utc) - dt.timedelta(seconds=30)
def at(seconds):
    return base + dt.timedelta(seconds=seconds)

with psycopg.connect(host='127.0.0.1', port=15452, dbname='telemetry_c', user='mocknet_c',
                     password=secret['MOCKNET_DB_PASSWORD']) as db:
    db.execute('SET LOCAL ROLE c_reporter')
    def event(source, kind, entity, seconds, body, sequence=1):
        db.execute('INSERT INTO evidence(event_id,source,kind,entity_id,operation_id,at,sequence,body) VALUES(%s,%s,%s,%s,%s,%s,%s,%s)',
                   (str(uuid.uuid4()), source, kind, entity, operation, at(seconds), sequence, Jsonb(body)))
    def call(name, start, end, parent=None, depth=0, missing_start=False):
        body = {'component':name, 'parent_call_id':parent, 'depth':depth, 'thread':'worker-1',
                'queue_message_id':operation, 'stage':'INGESTION'}
        if not missing_start:
            event('component_log', 'start', name, start, body)
        if end is not None:
            event('component_log', 'end', name, end,
                  {**body, 'duration_ms':max(0,(end-start)*1000), 'outcome':'returned'})
    call('parent', .1, .5)
    call('child', .2, .3, 'parent', 1)
    call('orphan', 1.3, 1.4, 'absent', 1)
    call('open', 1.5, None)
    call('end-only', 1.8, 2, missing_start=True)
    call('clock-skew', 2.5, 2.4)
    for n,start,end,wait,outcome in [(1,.05,.6,.05,'retried'), (2,1.2,3.1,.2,'completed')]:
        event('mq_journal','processing_attempts',operation+str(n),end,
              {'queue_message_id':operation,'queue_name':'INGESTION','attempt_number':n,
               'claimed_at':at(start).isoformat(),'finished_at':at(end).isoformat(),
               'wait_seconds':wait,'processing_seconds':end-start,'outcome':outcome,'worker':'worker-1'},n)
    # Verify the exact role Grafana uses, without giving it write privileges.
    db.execute('RESET ROLE')
    db.execute('SET LOCAL ROLE grafana_c')
    def query(op):
        cur=db.execute('SELECT * FROM c_waterfall(%s)',(op,))
        return [dict(zip([c.name for c in cur.description],r)) for r in cur]
    rows=query(operation)
    assert query(str(uuid.uuid4())) == []
    assert [r['spanID'] for r in rows] == [r['spanID'] for r in query(operation)]
    ids={r['spanID']:r for r in rows}
    assert len(ids)==len(rows)
    assert sum(r['parentSpanID'] is None for r in rows)==1
    for row in rows:
        assert row['duration']>=0 and row['startTime']>1e12
        seen=set();current=row
        while current['parentSpanID']:
            assert current['spanID'] not in seen
            seen.add(current['spanID']);current=ids[current['parentSpanID']]
    def named(name):
        return next(r for r in rows if r['operationName'].startswith(name))
    assert named('child')['parentSpanID']==named('parent')['spanID']
    assert ids[named('parent')['parentSpanID']]['operationName']=='MQ PROCESS / retried'
    assert abs(named('child')['duration']-100)<.001
    assert abs(named('MQ RETRY DELAY')['duration']-400)<.001
    assert named('orphan')['warnings']
    assert named('open')['warnings'] and named('open')['duration']>10000
    assert named('end-only')['warnings'] and abs(named('end-only')['duration']-200)<.001
    assert named('clock-skew')['warnings']
    assert any(t['key']=='error' and t['value'] is True for t in named('MQ PROCESS / retried')['tags'])
    result={'passed':True,'checks':['Grafana read-only role','stable unique IDs','single root','acyclic parents',
        'recorded child parent','attempt association','millisecond duration','retry backoff',
        'missing parent','missing start','missing end','clock inconsistency','retry error marker','unknown operation'],
        'fixtureRows':len(rows),'fixturesRolledBack':True}
    db.rollback()
(ROOT/'.bootstrap/observability/approach-c/waterfall-contract-validation.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result,indent=2))

#!/usr/bin/env python3
"""Create one isolated, fixed-time SLA demo dataset without changing live trades or dashboards."""
import datetime as dt
import json
import os
from pathlib import Path
import uuid

import psycopg
from psycopg import sql
from psycopg.types.json import Jsonb

ROOT = Path(__file__).resolve().parents[1]
STATE = ROOT / '.bootstrap/observability/approach-c'
SCHEMA = 'mocknet_c_demo'
DATASET = 'sla-scenarios-v1'
POLICIES = ('validation', 'matching', 'instructions')
STATUSES = {'Met', 'Breached', 'Excluded', 'Failed', 'Unknown', 'Stale', 'In progress', 'At risk'}


def scenario_plan():
    def case(key, label, expected=('Met', 'Met', 'Met'), **changes):
        return dict(key=key, label=label, expected=dict(zip(POLICIES, expected)),
                    **(dict(age=100, validation=1, matching=4, netted=10,
                            output=6, generated=9, required=True, baseline=False,
                            rejected=False, failed=None, retries=(), stale=False,
                            partial=False, sent_only=False, related=None, own_netting=True) | changes))
    return [
        case('healthy', 'Healthy completion with overlapping netting and generation'),
        case('validation_breach', 'Validation exceeds five seconds', ('Breached', 'Met', 'Met'), validation=7, matching=10, netted=16, output=12, generated=15),
        case('matching_breach', 'Counterparty arrives after matching deadline', ('Met', 'Breached', 'Met'), matching=35, netted=40, output=37, generated=39),
        case('instruction_breach', 'Matching on time; instruction generation takes too long', ('Met', 'Met', 'Breached'), netted=10, output=6, generated=70),
        case('all_breached', 'All three milestones exceed their deadlines', ('Breached',)*3, validation=8, matching=40, netted=69, output=45, generated=72),
        case('exact_deadlines', 'Completion exactly at each deadline is on time', validation=5, matching=30, netted=60, output=45, generated=60),
        case('retry_recovered', 'Two ingestion retries then completion on time', validation=3, matching=8, netted=14, output=10, generated=13, retries=(.5, 1.2)),
        case('retry_exhausted', 'Ingestion retries exhausted', ('Failed',)*3, validation=None, matching=None, netted=None, output=None, generated=None, failed=('INGESTION', 3), retries=(.5, 1.2)),
        case('rejected', 'Rejected validation excludes downstream milestones', ('Met', 'Excluded', 'Excluded'), rejected=True, matching=None, netted=None, output=None, generated=None),
        case('late_rejection', 'Late rejection breaches validation only', ('Breached', 'Excluded', 'Excluded'), validation=7, rejected=True, matching=None, netted=None, output=None, generated=None),
        case('validation_in_progress', 'Validation still running before warning threshold', ('In progress',)*3, age=2, validation=None, matching=None, netted=None, output=None, generated=None),
        case('validation_at_risk', 'Validation has used 90 percent of its allowance', ('At risk', 'In progress', 'In progress'), age=4.5, validation=None, matching=None, netted=None, output=None, generated=None),
        case('matching_in_progress', 'Waiting for counterparty within matching target', ('Met', 'In progress', 'In progress'), age=10, matching=None, netted=None, output=None, generated=None),
        case('matching_at_risk', 'Counterparty wait exceeds 80 percent of matching target', ('Met', 'At risk', 'In progress'), age=26, matching=None, netted=None, output=None, generated=None),
        case('instructions_at_risk', 'Matched; netting and instruction generation still open', ('Met', 'Met', 'At risk'), age=53, netted=None, output=12, generated=None),
        case('waiting_breached', 'Unmatched trade exceeds both downstream targets', ('Met', 'Breached', 'Breached'), age=90, matching=None, netted=None, output=None, generated=None),
        case('netting_failed', 'Netting fails after matching completes', ('Met', 'Met', 'Failed'), netted=None, output=None, generated=None, failed=('NETTING', 12)),
        case('shared_failure_owner', 'Shared netting failure on owning leg', ('Met', 'Met', 'Failed'), netted=None, output=None, generated=None, failed=('NETTING', 12), related='shared_failure_other'),
        case('shared_failure_other', 'Shared netting failure visible on matched leg', ('Met', 'Met', 'Failed'), netted=None, output=None, generated=None, own_netting=False),
        case('zero_net', 'Zero net amount requires no instruction', required=False, generated=None),
        case('partial_output', 'Only one of two required instructions is recorded', ('Met', 'Met', 'Breached'), partial=True),
        case('missing_generation', 'Sent state exists but generation time is unknown', ('Met', 'Met', 'Unknown'), sent_only=True),
        case('imported_baseline', 'Imported state has no measured historical SLA', ('Unknown',)*3, baseline=True),
        case('stale_observation', 'Open trade observed by a stale collector', ('Stale',)*3, age=26, validation=None, matching=None, netted=None, output=None, generated=None, stale=True),
    ]


def identity(value):
    return str(uuid.uuid5(uuid.NAMESPACE_URL, DATASET + ':' + value))


def events_for(case, as_of):
    op = identity(case['key'])
    base = as_of - dt.timedelta(seconds=case['age'])
    rows = []
    def stamp(seconds):
        return base + dt.timedelta(seconds=seconds)
    def event(kind, entity, seconds, body):
        rows.append(dict(source='mq_journal', kind=kind, entity_id=identity(case['key'] + ':' + entity),
                         operation_id=op, at=stamp(seconds), body=dict(body,
                         demo_fixture=True, dataset_id=DATASET, scenario=case['key'],
                         provenance='synthetic fixed-time demo', baseline=case['baseline'])))
    trade = 'trade'
    def queue(stage, began, ended, outcome):
        entity = 'queue-' + stage
        qid = identity(case['key'] + ':' + entity)
        body = dict(id=qid, operation_id=op, business_id='DEMO-SLA-' + case['key'].upper(),
                    queue_name=stage, attempts=0, created_at=stamp(began).isoformat(),
                    available_at=stamp(began).isoformat(), worker_name='fixed-demo-' + stage.lower())
        event('queue_messages', entity, began, dict(body, status='NEW'))
        claimed = began + .05
        event('queue_messages', entity, claimed, dict(body, status='PROCESSING', claimed_at=stamp(claimed).isoformat()))
        retry_times = case['retries'] if stage == 'INGESTION' else ()
        for index, end in enumerate(retry_times):
            event('processing_attempts', entity + '-retry-' + str(index), end,
                  dict(queue_message_id=qid, queue_name=stage, attempt_number=index+1,
                       claimed_at=stamp(claimed).isoformat(), finished_at=stamp(end).isoformat(),
                       outcome='retried', reason='CONCURRENCY_CONFLICT', worker=body['worker_name'],
                       wait_seconds=.1, processing_seconds=end-claimed))
            claimed = end + .1
        attempt = dict(queue_message_id=qid, queue_name=stage, attempt_number=len(retry_times)+1,
                       claimed_at=stamp(claimed).isoformat(), outcome=outcome,
                       reason='DEMO_FAILURE' if outcome=='failed' else None,
                       worker=body['worker_name'], wait_seconds=.05, processing_seconds=None)
        if ended is not None:
            attempt.update(finished_at=stamp(ended).isoformat(), processing_seconds=ended-claimed)
            event('queue_messages', entity, ended, dict(body, status='FAILED' if outcome=='failed' else 'DONE',
                  outcome=outcome, attempts=len(retry_times), claimed_at=stamp(claimed).isoformat()))
        event('processing_attempts', entity + '-attempt', ended if ended is not None else claimed, attempt)
    failed = case['failed']
    v, m, n, g = (case[name] for name in ('validation', 'matching', 'netted', 'generated'))
    queue('INGESTION', 0, failed[1] if failed and failed[0]=='INGESTION' else v,
          'failed' if failed and failed[0]=='INGESTION' else 'rejected' if case['rejected'] else 'completed' if v is not None else 'processing')
    event('trades', trade, .01, {'status':'RECEIVED'})
    if v is not None:
        event('trades', trade, v, {'status':'REJECTED' if case['rejected'] else 'VALIDATED'})
    if v is not None and not case['rejected']:
        queue('MATCHING', v+.01, m if m is not None else v+.2, 'completed')
    if m is not None:
        event('trades', trade, m, {'status':'MATCHED'})
        if case['own_netting']:
            end = failed[1] if failed and failed[0]=='NETTING' else max(n,g or n) if n is not None and (g is not None or not case['required']) else None
            queue('NETTING', m+.01, end, 'failed' if failed and failed[0]=='NETTING' else 'completed' if end is not None else 'processing')
    if n is not None:
        event('trades', trade, n, {'status':'NETTED'})
    if case['related']:
        event('matched_trades', 'match', m+.01, {'related_operation_id':identity(case['related'])})
    if case['output'] is not None:
        for index in range(2 if case['required'] else 1):
            net_id = identity(case['key'] + ':net-' + str(index))
            event('netting_sets', 'net-' + str(index), case['output'],
                  {'netting_set_id':net_id, 'instruction_required':case['required']})
            if case['required'] and g is not None and not (case['partial'] and index==1):
                event('settlement_instructions', 'instruction-' + str(index), g,
                      {'netting_set_id':net_id,'status':'SENT' if case['sent_only'] else 'GENERATED'})
    return rows


def document(db):
    dataset = db.execute('SELECT as_of FROM mocknet_c_demo.dataset WHERE dataset_id=%s', (DATASET,)).fetchone()
    cases = db.execute('SELECT scenario_id,label,operation_id,expected_sla,observed_sla FROM mocknet_c_demo.scenarios ORDER BY ordinal').fetchall()
    counts = db.execute('SELECT policy_id,sla_status,count(*) FROM mocknet_c_demo.c_process_sla GROUP BY 1,2 ORDER BY 1,2').fetchall()
    first = db.execute('SELECT min(admitted_at) FROM mocknet_c_demo.c_operations').fetchone()[0]
    return dict(datasetId=DATASET, schema=SCHEMA, asOf=dataset[0].isoformat(),
                timeRange={'from':str(int(first.timestamp()*1000)-1000), 'to':str(int(dataset[0].timestamp()*1000)+1000)},
                provenance='synthetic fixed-time demo; not application traffic',
                scenarioCount=len(cases), statusCoverage=[dict(policy=p,status=s,count=n) for p,s,n in counts],
                scenarios=[dict(id=k,label=label,operationId=op,expectedSla=expected,observedSla=observed)
                           for k,label,op,expected,observed in cases])


def main():
    if os.environ.get('MOCKNET_C_MODE', 'local-demo') != 'local-demo':
        raise RuntimeError('Fixed demo fixtures require local-demo mode')
    secret = dict(line.split('=',1) for line in (ROOT/'.bootstrap/observability/grafana.env').read_text().splitlines())
    with psycopg.connect(host='127.0.0.1',port=15452,dbname='telemetry_c',user='mocknet_c',password=secret['MOCKNET_DB_PASSWORD']) as db:
        db.execute('SELECT pg_advisory_xact_lock(67123003)')
        if db.execute("SELECT to_regclass('mocknet_c_demo.dataset')").fetchone()[0]:
            result = document(db)
        else:
            db.execute('SET LOCAL jit=off')
            db.execute('SET LOCAL max_parallel_workers_per_gather=0')
            as_of = db.execute('SELECT now()').fetchone()[0]
            scratch = '_c_fixture_' + uuid.uuid4().hex
            db.execute(sql.SQL('CREATE SCHEMA {}').format(sql.Identifier(scratch)))
            db.execute(sql.SQL('SET LOCAL search_path TO {}, public').format(sql.Identifier(scratch)))
            # Build and evaluate the real reporting definitions against isolated mock evidence.
            # No public evidence, live collector row or export queue is modified.
            db.execute((ROOT/'observability/approach-c/reporting.sql').read_text())
            db.execute((ROOT/'observability/approach-c/business.sql').read_text())
            db.execute("INSERT INTO collector_status VALUES(1,%s,0,0,'fixed demo observation')", (as_of,))
            cases = scenario_plan()
            events = sorted([event for case in cases for event in events_for(case,as_of)], key=lambda e:e['at'])
            for index,event in enumerate(events,1):
                db.execute('INSERT INTO evidence(event_id,source,at,sequence,kind,entity_id,operation_id,body) VALUES(%s,%s,%s,%s,%s,%s,%s,%s)',
                           (identity('event-'+str(index)),event['source'],event['at'],index,event['kind'],event['entity_id'],event['operation_id'],Jsonb(event['body'])))
            db.execute('CREATE SCHEMA mocknet_c_demo')
            db.execute('CREATE TABLE mocknet_c_demo.dataset(dataset_id text PRIMARY KEY,as_of timestamptz NOT NULL,provenance text NOT NULL)')
            db.execute('CREATE TABLE mocknet_c_demo.scenarios(scenario_id text PRIMARY KEY,ordinal int,label text,operation_id text UNIQUE,expected_sla jsonb,observed_sla jsonb)')
            tables = ('c_process_summary','c_process_sla','c_process_events','c_operations','c_attempt','c_queue','evidence')
            for name in tables:
                db.execute(sql.SQL('CREATE TABLE mocknet_c_demo.{} AS SELECT * FROM {} WITH NO DATA').format(sql.Identifier(name),sql.Identifier(name)))
            for name in ('c_process_stages','c_process_timeline'):
                db.execute(sql.SQL("CREATE TABLE mocknet_c_demo.{} AS SELECT NULL::text AS operation_id,f.* FROM {}('') f WITH NO DATA").format(sql.Identifier(name),sql.Identifier(name)))
            for ordinal,case in enumerate(cases):
                op = identity(case['key'])
                db.execute('UPDATE collector_status SET at=%s WHERE id=1', (as_of-dt.timedelta(seconds=20) if case['stale'] else as_of,))
                observed = dict(db.execute('SELECT policy_id,sla_status FROM c_process_sla WHERE operation_id=%s',(op,)).fetchall())
                if observed != case['expected']:
                    raise AssertionError((case['key'],case['expected'],observed))
                db.execute('INSERT INTO mocknet_c_demo.scenarios VALUES(%s,%s,%s,%s,%s,%s)',
                           (case['key'],ordinal,case['label'],op,Jsonb(case['expected']),Jsonb(observed)))
                for name in tables:
                    db.execute(sql.SQL('INSERT INTO mocknet_c_demo.{} SELECT * FROM {} WHERE operation_id=%s').format(sql.Identifier(name),sql.Identifier(name)),(op,))
                for name in ('c_process_stages','c_process_timeline'):
                    db.execute(sql.SQL('INSERT INTO mocknet_c_demo.{} SELECT %s,f.* FROM {}(%s) f').format(sql.Identifier(name),sql.Identifier(name)),(op,op))
            for name in ('c_operation_links','c_sla_policy'):
                db.execute(sql.SQL('CREATE TABLE mocknet_c_demo.{} AS SELECT * FROM {}').format(sql.Identifier(name),sql.Identifier(name)))
            db.execute('UPDATE collector_status SET at=%s WHERE id=1',(as_of,))
            db.execute('CREATE TABLE mocknet_c_demo.c_evidence_health AS SELECT * FROM c_evidence_health')
            db.execute('INSERT INTO mocknet_c_demo.dataset VALUES(%s,%s,%s)',(DATASET,as_of,'synthetic fixed-time demo'))
            for name in (*tables,'c_process_stages','c_process_timeline'):
                db.execute(sql.SQL('CREATE INDEX ON mocknet_c_demo.{} (operation_id)').format(sql.Identifier(name)))
            db.execute('''CREATE FUNCTION mocknet_c_demo.c_process_stages(selected_operation text)
                RETURNS TABLE(stage_order int,stage text,start_time timestamptz,end_time timestamptz,status text,elapsed_ms double precision,retries bigint,readiness_at timestamptz)
                LANGUAGE sql STABLE AS $fn$ SELECT stage_order,stage,start_time,end_time,status,elapsed_ms,retries,readiness_at
                FROM mocknet_c_demo.c_process_stages WHERE operation_id=selected_operation ORDER BY stage_order $fn$''')
            db.execute('''CREATE FUNCTION mocknet_c_demo.c_process_timeline(selected_operation text)
                RETURNS TABLE(start_time timestamptz,end_time timestamptz,state text)
                LANGUAGE sql STABLE AS $fn$ SELECT start_time,end_time,state FROM mocknet_c_demo.c_process_timeline
                WHERE operation_id=selected_operation ORDER BY start_time $fn$''')
            db.execute('GRANT USAGE ON SCHEMA mocknet_c_demo TO grafana_c,c_reporter')
            db.execute('GRANT SELECT ON ALL TABLES IN SCHEMA mocknet_c_demo TO grafana_c,c_reporter')
            db.execute('REVOKE ALL ON ALL FUNCTIONS IN SCHEMA mocknet_c_demo FROM PUBLIC')
            db.execute('GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA mocknet_c_demo TO grafana_c,c_reporter')
            db.execute(sql.SQL('DROP SCHEMA {} CASCADE').format(sql.Identifier(scratch)))
            db.execute('SET LOCAL search_path TO public')
            result = document(db)
            assert {entry['status'] for entry in result['statusCoverage']} == STATUSES
    path = STATE/'seeds'/f'{DATASET}.json'
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps({'manifest':str(path),'schema':SCHEMA,'asOf':result['asOf'],'scenarios':result['scenarioCount'],'statuses':sorted(STATUSES)},indent=2))


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Verify fixed demo scenarios and their complete retained field/timing contract."""
import hashlib
import json
from pathlib import Path

import psycopg

ROOT = Path(__file__).resolve().parents[1]
STATE = ROOT/'.bootstrap/observability/approach-c'


def main():
    secret = dict(line.split('=',1) for line in (ROOT/'.bootstrap/observability/grafana.env').read_text().splitlines())
    with psycopg.connect(host='127.0.0.1',port=15452,dbname='telemetry_c',user='grafana_c',password=secret['MOCKNET_READER_PASSWORD']) as db:
        assert db.execute('SHOW default_transaction_read_only').fetchone()[0]=='on'
        as_of = db.execute('SELECT as_of FROM mocknet_c_demo.dataset').fetchone()[0]
        cases = db.execute('SELECT scenario_id,operation_id,expected_sla FROM mocknet_c_demo.scenarios ORDER BY ordinal').fetchall()
        assert len(cases)==24
        statuses=set(); result=[]
        for scenario,op,expected in cases:
            sla = db.execute('SELECT policy_id,sla_status,received_at,finished_at,elapsed_seconds,collector_age_seconds FROM mocknet_c_demo.c_process_sla WHERE operation_id=%s',(op,)).fetchall()
            assert dict((r[0],r[1]) for r in sla)==expected,(scenario,sla,expected)
            statuses.update(r[1] for r in sla)
            stages = db.execute('SELECT * FROM mocknet_c_demo.c_process_stages(%s)',(op,)).fetchall()
            assert len(stages)==4 and [r[0] for r in stages]==[1,2,3,4]
            for order,stage,start,end,status,milliseconds,retries,ready in stages:
                if start and end:
                    assert start<=end<=as_of,(scenario,stage,start,end)
                    assert abs(milliseconds-(end-start).total_seconds()*1000)<.001
                if status=='Completed':
                    assert start is not None and end is not None and milliseconds is not None
                if status in ('Not required','Not reached','Not applicable','Missing evidence'):
                    assert milliseconds is None,(scenario,stage,status)
            for policy,status,received,finished,elapsed,age in sla:
                if finished and received:
                    assert abs(elapsed-(finished-received).total_seconds())<.000001
                if status=='At risk':
                    limit={'validation':5,'matching':30,'instructions':60}[policy]
                    assert finished is None and .8*limit<=elapsed<=limit
                if status=='Stale':
                    assert age>10
            events = db.execute('SELECT at,sequence,milestone,event_id,body FROM mocknet_c_demo.c_process_events WHERE operation_id=%s ORDER BY sequence',(op,)).fetchall()
            assert events and all(row[4]['demo_fixture'] for row in events)
            assert all(row[0]<=as_of for row in events)
            assert db.execute('SELECT count(*) FROM mocknet_c_demo.c_attempt WHERE operation_id=%s',(op,)).fetchone()[0]>0
            result.append(dict(scenario=scenario,operationId=op,sla=expected,stages=len(stages),events=len(events)))
        assert statuses=={'Met','Breached','Failed','Excluded','Unknown','Stale','At risk','In progress'}
        by_id={key:op for key,op,_ in cases}
        assert dict(db.execute('SELECT policy_id,elapsed_seconds FROM mocknet_c_demo.c_process_sla WHERE operation_id=%s',(by_id['exact_deadlines'],)))=={'validation':5,'matching':30,'instructions':60}
        late=dict(db.execute('SELECT policy_id,elapsed_seconds FROM mocknet_c_demo.c_process_sla WHERE operation_id=%s',(by_id['instruction_breach'],)))
        assert late['matching']==4 and late['instructions']==70
        healthy=db.execute('SELECT stage,start_time,end_time FROM mocknet_c_demo.c_process_stages(%s)',(by_id['healthy'],)).fetchall()
        intervals={row[0]:row[1:] for row in healthy}
        assert intervals['Netting'][0]<intervals['Instruction generation'][0]<intervals['Instruction generation'][1]<intervals['Netting'][1]
        assert db.execute('SELECT retries FROM mocknet_c_demo.c_process_summary WHERE operation_id=%s',(by_id['retry_recovered'],)).fetchone()[0]==2
        partial=db.execute('SELECT required_outputs,generated_outputs,instructions_at FROM mocknet_c_demo.c_process_summary WHERE operation_id=%s',(by_id['partial_output'],)).fetchone()
        assert partial==(2,1,None)
        assert db.execute("SELECT status FROM mocknet_c_demo.c_process_stages(%s) WHERE stage='Instruction generation'",(by_id['zero_net'],)).fetchone()[0]=='Not required'
        for key in ('shared_failure_owner','shared_failure_other'):
            assert db.execute("SELECT status,end_time FROM mocknet_c_demo.c_process_stages(%s) WHERE stage='Netting'",(by_id[key],)).fetchone()[0]=='Failed'
        # The SLA and stage data are stored rows, so a later read cannot advance them.
        fingerprints=[]
        for relation in ('c_process_sla','c_process_stages','c_process_events'):
            kind=db.execute('SELECT relkind FROM pg_class WHERE oid=%s::regclass',('mocknet_c_demo.'+relation,)).fetchone()[0]
            assert kind=='r'
            rows=db.execute('SELECT row_to_json(t)::text FROM mocknet_c_demo.'+relation+' t ORDER BY row_to_json(t)::text').fetchall()
            fingerprints.extend(row[0] for row in rows)
    output=dict(passed=True,asOf=as_of.isoformat(),scenarioCount=len(cases),slaRows=len(cases)*3,stageRows=len(cases)*4,
                statuses=sorted(statuses),fingerprint=hashlib.sha256('\n'.join(fingerprints).encode()).hexdigest(),scenarios=result)
    (STATE/'fixed-sla-validation.json').write_text(json.dumps(output,indent=2)+'\n')
    print(json.dumps({k:v for k,v in output.items() if k!='scenarios'},indent=2))


if __name__=='__main__':
    main()

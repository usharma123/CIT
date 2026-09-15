#!/usr/bin/env python3
"""Live C REST, least-privilege, diagnostic audit and source freshness checks."""
import json
import pathlib
import sys
import time
import urllib.error
import urllib.request

import psycopg

ROOT=pathlib.Path(__file__).resolve().parents[1]
STATE=ROOT/'.bootstrap/observability/approach-c'
sys.path.insert(0,str(ROOT/'observability/approach-c/backend'))
from sources import secrets


def main():
    secret=secrets()
    checks=[]
    def call(path,token=None,body=None):
        headers={'Content-Type':'application/json'}
        if token:headers['Authorization']='Bearer '+token
        request=urllib.request.Request('http://127.0.0.1:18103'+path,
            data=None if body is None else json.dumps(body).encode(),headers=headers)
        try:
            with urllib.request.urlopen(request,timeout=15) as response:return response.status,json.load(response)
        except urllib.error.HTTPError as error:return error.code,json.load(error)
    reader=secret['MOCKNET_C_API_READER_TOKEN']
    operator=secret['MOCKNET_C_API_OPERATOR_TOKEN']
    assert call('/api/v1/sources')[0]==401
    assert call('/api/v1/tools/queue-diagnostics/runs',reader,{})[0]==403
    assert call('/api/v1/tools/arbitrary-shell/runs',operator,{})[0]==404
    assert call('/api/v1/tools/queue-diagnostics/runs',operator,{'command':'whoami'})[0]==400
    checks.append('authentication, operator authorization and fixed tool allowlist')
    code,body=call('/api/v1/sources',reader)
    assert code==200
    sources={s['source_id']:s for s in body['sources']}
    assert all(sources[s]['status']=='ok' for s in ('database','ods','application','configuration')),sources
    assert all(sources[s]['status']=='not configured' for s in ('cor','lg2','udg'))
    checks.append('four live local adapters and three explicitly unconfigured corporate sources')
    admin=dict(host='127.0.0.1',port=15452,password=secret['MOCKNET_C_BACKEND_DB_PASSWORD'])
    with psycopg.connect(**admin,user='c_source_reader',dbname='mocknet_c') as db:
        assert db.execute('SELECT count(*) FROM c_support.database_summary').fetchone()[0]>0
        assert not db.execute("SELECT has_table_privilege(current_user,'trades','SELECT')").fetchone()[0]
        assert not db.execute("SELECT has_database_privilege(current_user,'telemetry_c','CONNECT')").fetchone()[0]
        assert db.execute('SHOW default_transaction_read_only').fetchone()[0]=='on'
    with psycopg.connect(**admin,user='c_backend_reader',dbname='telemetry_c') as db:
        assert not db.execute("SELECT has_database_privilege(current_user,'mocknet_c','CONNECT')").fetchone()[0]
        assert not db.execute("SELECT has_table_privilege(current_user,'evidence','SELECT')").fetchone()[0]
        operation=db.execute('SELECT operation_id FROM c_operations ORDER BY admitted_at DESC LIMIT 1').fetchone()[0]
    with psycopg.connect(**admin,user='c_backend_writer',dbname='telemetry_c') as db:
        assert not db.execute("SELECT has_table_privilege(current_user,'evidence','INSERT')").fetchone()[0]
    checks.append('source reader restricted to aggregate view; backend reporting reader and audit writer separated')
    runs=[]
    for tool,args in [('application-health',{}),('queue-diagnostics',{}),('operation-evidence',{'operation_id':operation})]:
        code,result=call('/api/v1/tools/'+tool+'/runs',operator,args)
        assert code==200 and result['status']=='completed',(code,result)
        runs.append(result['run_id'])
    assert call('/api/v1/operations/'+operation,reader)[0]==200
    assert call('/api/v1/operations/definitely-unknown-operation',reader)[0]==404
    code,audit=call('/api/v1/tools/runs',reader)
    assert code==200 and set(runs)<=set(r['run_id'] for r in audit['runs'])
    checks.append('three diagnostic executions persisted and available to readers; operation lookup checked')
    with psycopg.connect(host='127.0.0.1',port=15452,user='c_reporter',dbname='telemetry_c',password=secret['MOCKNET_C_COLLECTOR_PASSWORD']) as db:
        db.execute("UPDATE source_snapshots SET attempted_at=now()-interval '2 minutes' WHERE source_id='database'")
        assert db.execute("SELECT status FROM c_source_status WHERE source_id='database'").fetchone()[0]=='collector stale'
        db.execute("UPDATE source_snapshots SET attempted_at=now(),sampled_at=now()-interval '2 minutes',status='ok' WHERE source_id='database'")
        assert db.execute("SELECT status FROM c_source_status WHERE source_id='database'").fetchone()[0]=='stale'
        db.execute("UPDATE source_snapshots SET attempted_at=now()-interval '2 minutes' WHERE source_id='cor'")
        assert db.execute("SELECT status FROM c_source_status WHERE source_id='cor'").fetchone()[0]=='not configured'
        db.rollback()
    checks.append('stale collection is visible; temporary fixture rolled back')
    output={'at':time.time(),'checks':checks,'diagnostic_run_ids':runs,'operation_id':operation}
    (STATE/'support-api-validation.json').write_text(json.dumps(output,indent=2)+'\n')
    print(json.dumps(output,indent=2))


if __name__=='__main__':main()

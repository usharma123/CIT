import datetime as dt
import json
import pathlib
import sys

import psycopg
import pytest

sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[1]))
from app import create_app
from sources import Sources, now

CREDS={'MOCKNET_C_API_READER_TOKEN':'r'*48,'MOCKNET_C_API_OPERATOR_TOKEN':'o'*48}


class FakeSources(Sources):
    def __init__(self,state):
        super().__init__(credentials=CREDS,state=state)
        self.calls=[]
        self.fail_tool=False
        self.fail_store=False

    def execute_tool(self,tool,operation_id):
        self.calls.append(('tool',tool,operation_id))
        if self.fail_tool:
            raise OSError('private connection detail')
        return [{'status':'UP'}]

    def query(self,query,params=(),source=False):
        self.calls.append(('query',query,params))
        return []

    def connect(self,*_):
        if self.fail_store:
            raise psycopg.OperationalError('private credentials')
        backend=self
        class Connection:
            def __enter__(self):return self
            def __exit__(self,*_):pass
            def execute(self,query,params):backend.calls.append(('write',query,params))
        return Connection()


@pytest.fixture
def service(tmp_path):
    sources=FakeSources(tmp_path)
    app=create_app(sources)
    app.testing=True
    return app.test_client(),sources


def headers(role='reader'):
    return {'Authorization':'Bearer '+CREDS['MOCKNET_C_API_'+role.upper()+'_TOKEN']}


def test_api_authentication_and_role_boundary(service):
    client,sources=service
    assert client.get('/health').status_code==200
    for path in ('sources','tools','tools/runs','operations/a'):
        assert client.get('/api/v1/'+path).status_code==401
    assert client.post('/api/v1/tools/application-health/runs',json={},headers=headers()).status_code==403
    assert sources.calls==[]
    assert client.get('/api/v1/tools',headers=headers()).status_code==200
    assert client.get('/api/v1/tools',headers={'Authorization':'Bearer café'}).status_code==401


@pytest.mark.parametrize('tool,body,status',[
    ('shell',{},404),('application-health',{'command':'ls'},400),
    ('application-health',{'operation_id':'a'},400),('operation-evidence',{},400),
    ('operation-evidence',{'operation_id':"' OR 1=1--"},400),
    ('queue-diagnostics',[],400),('queue-diagnostics',None,400),
])
def test_tool_inputs_cannot_select_commands_or_queries(service,tool,body,status):
    client,sources=service
    response=client.post('/api/v1/tools/'+tool+'/runs',json=body,headers=headers('operator'))
    assert response.status_code==status
    assert sources.calls==[]


def test_audit_precedes_execution_and_records_failure_without_details(service):
    client,sources=service
    sources.fail_tool=True
    response=client.post('/api/v1/tools/application-health/runs',json={},headers=headers('operator'))
    assert response.status_code==502
    assert [c[0] for c in sources.calls]==['write','tool','write']
    assert sources.calls[0][2][-1]=='running'
    assert sources.calls[-1][2][0]=='failed'
    assert b'private' not in response.data


def test_store_failure_prevents_tool_execution(service):
    client,sources=service
    sources.fail_store=True
    response=client.post('/api/v1/tools/application-health/runs',json={},headers=headers('operator'))
    assert response.status_code==503
    assert sources.calls==[]
    assert b'private' not in response.data


def test_operation_lookup_is_parameterized_and_unknown_is_404(service):
    client,sources=service
    response=client.get('/api/v1/operations/abc-123',headers=headers())
    assert response.status_code==404
    assert sources.calls[0][2]==('abc-123',)
    assert 'abc-123' not in sources.calls[0][1]


def test_one_source_failure_does_not_hide_other_sources(service):
    _,sources=service
    def failure():raise psycopg.OperationalError('password=private')
    sources.database=failure
    rows={r['source_id']:r for r in sources.snapshots()}
    assert rows['database']['status']=='unavailable'
    assert rows['configuration']['status']=='ok'
    assert rows['cor']['status']=='not configured'
    assert 'private' not in json.dumps(rows)
    assert all('TOKEN' not in r['item'] for r in rows['configuration']['rows'])


def test_ods_preserves_source_time_and_marks_old_snapshot_stale(service):
    _,sources=service
    directory=sources.state/'ods'
    directory.mkdir()
    stamp=(dt.datetime.now(dt.timezone.utc)-dt.timedelta(minutes=2)).isoformat()
    (directory/'snapshot.json').write_text(json.dumps({'schema_version':1,'provenance':'local-emulation',
        'sampled_at':stamp,'rows':[{'category':'trade','item':'NETTED','value':3}]}))
    ods=next(r for r in sources.snapshots() if r['source_id']=='ods')
    assert ods['status']=='stale'
    assert ods['sampled_at']==stamp
    assert ods['rows'][0]['value']==3


@pytest.mark.parametrize('change',[
    {'rows':[{'category':'trade','item':'NETTED','value':3,'payload':'secret'}]},
    {'sampled_at':'2026-01-01T00:00:00'}, {'rows':'invalid'}, {'schema_version':2},
])
def test_ods_rejects_invalid_or_unexpected_fields(service,change):
    _,sources=service
    directory=sources.state/'ods'
    directory.mkdir()
    body={'schema_version':1,'provenance':'local-emulation','sampled_at':now(),'rows':[]}
    body.update(change)
    (directory/'snapshot.json').write_text(json.dumps(body))
    ods=next(r for r in sources.snapshots() if r['source_id']=='ods')
    assert ods['status']=='unavailable'
    assert ods['rows']==[]


def test_distinct_nonempty_credentials_required(tmp_path):
    sources=FakeSources(tmp_path)
    sources.credentials={**CREDS,'MOCKNET_C_API_OPERATOR_TOKEN':CREDS['MOCKNET_C_API_READER_TOKEN']}
    with pytest.raises(ValueError):create_app(sources)


def test_invalid_ods_document_does_not_break_other_adapters(service):
    _,sources=service
    directory=sources.state/'ods'
    directory.mkdir()
    (directory/'snapshot.json').write_text('[]')
    rows={r['source_id']:r for r in sources.snapshots()}
    assert rows['ods']['status']=='unavailable'
    assert rows['configuration']['status']=='ok'


def test_diagnostic_api_and_audit_preserve_subsecond_timestamps(service):
    client,sources=service
    stamp=dt.datetime(2026,9,15,17,0,0,123456,tzinfo=dt.timezone.utc)
    sources.execute_tool=lambda *_:[{'started_at':stamp}]
    response=client.post('/api/v1/tools/operation-evidence/runs',json={'operation_id':'op-1'},headers=headers('operator'))
    assert response.status_code==200
    assert response.json['result'][0]['started_at']==stamp.isoformat()
    assert sources.calls[-1][2][1].obj[0]['started_at']==stamp.isoformat()

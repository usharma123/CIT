import datetime as dt
import importlib.util
import pathlib
import sys
import urllib.error
from unittest.mock import MagicMock, patch
import pytest

sys.path.insert(0, str(pathlib.Path(__file__).parents[1]))
import bridge


def rows():
    return [dict(traceID='1'*32,spanID='2'*16,parentSpanID=None,operationName='Reconstructed operation',serviceName='C evidence',
                 startTime=1789500000123.125,duration=25.5,tags=[{'key':'operation_id','value':'op'}],warnings=[]),
            dict(traceID='1'*32,spanID='3'*16,parentSpanID='2'*16,operationName='matching',serviceName='MATCHING',
                 startTime=1789500000124.125,duration=2.5,tags=[{'key':'error','value':True}],warnings=['inferred parent'])]


def test_one_service_preserves_id_parent_precision_and_provenance():
    body=bridge.trace_payload(rows(),'queue work finished')
    resource=body['resourceSpans'][0]
    assert resource['resource']['attributes'][0:]
    spans=resource['scopeSpans'][0]['spans']
    assert len(spans)==2 and spans[1]['parentSpanId']==spans[0]['spanId']
    assert spans[0]['startTimeUnixNano']=='1789500000123125000'
    assert spans[0]['status']['code']==0 and spans[1]['status']['code']==2
    assert all(s['kind']==1 for s in spans)
    attrs={a['key']:a['value'] for a in spans[1]['attributes']}
    assert attrs['component.stage']=={'stringValue':'MATCHING'}
    assert attrs['evidence.reconstructed']=={'boolValue':True}
    assert not any(a['key'].startswith('db.') for s in spans for a in s['attributes'])
    assert bridge.trace_payload(rows(),'failed')['resourceSpans'][0]['scopeSpans'][0]['spans'][0]['status']['code']==2


@pytest.mark.parametrize('mutation',[lambda r:r.append(r[1]),lambda r:r[1].update(parentSpanID='4'*16),lambda r:r[1].update(duration=-1)])
def test_reject_invalid_tree(mutation):
    data=rows();mutation(data)
    with pytest.raises(ValueError):bridge.trace_payload(data,'queue work finished')


def test_logs_keep_high_cardinality_ids_out_of_labels_and_escape_json():
    stamp=dt.datetime(2026,9,15,12,0,0,123456,tzinfo=dt.timezone.utc)
    data=[('event:1','component_log',stamp,'operation-a',{'stage':'INGESTION','message':'quote " newline\n'})]
    first=bridge.log_payload(data)
    assert first==bridge.log_payload(data)
    stream=first['streams'][0]
    assert 'operation_id' not in stream['stream'] and 'trace_id' not in stream['stream']
    assert stream['values'][0][2]['trace_id']==bridge.trace_id('operation-a')
    assert stream['values'][0][0].endswith('123456000')


@pytest.mark.parametrize('failure,expected',[
    (urllib.error.URLError(ConnectionRefusedError()),'pending'),
    (urllib.error.URLError(TimeoutError()),'uncertain'),
    (TimeoutError(),'uncertain'),
    (urllib.error.HTTPError('url',503,'unavailable',{},None),'uncertain'),
    (urllib.error.HTTPError('url',400,'invalid',{},None),'rejected'),
])
def test_ambiguous_delivery_never_becomes_retryable(failure,expected):
    with patch('urllib.request.urlopen',side_effect=failure):assert bridge.post(bridge.TEMPO,{})[0]==expected


def test_partial_success_does_not_retry_entire_trace():
    class Response:
        def __enter__(self):return self
        def __exit__(self,*_):pass
        def read(self):return b'{"partialSuccess":{"rejectedSpans":1}}'
    with patch('urllib.request.urlopen',return_value=Response()):
        assert bridge.post(bridge.TEMPO,{})[0]=='uncertain'


@pytest.mark.parametrize('state,progress', [('exported', 1), ('pending', 0), ('uncertain', 0), ('rejected', 0)])
def test_log_batch_records_delivery_for_exact_ids_before_advancing(state, progress):
    db = MagicMock()
    db.execute.return_value.fetchall.return_value = [
        ('event-1', 'application_log', dt.datetime.now(dt.timezone.utc), 'op', {'level': 'INFO'})]
    with patch.object(bridge, 'backend_ready', return_value=True), \
         patch.object(bridge, 'post', return_value=(state, None)) as post:
        assert bridge.send_logs(db) == progress
    calls = db.execute.call_args_list
    assert len(calls) == 3
    assert calls[1].args[1][1] == ['event-1']
    assert calls[2].args[1][0] == state
    assert calls[2].args[1][2] == ['event-1']
    assert calls[2].args[1][3] == calls[1].args[1][0]
    assert post.call_args.args[0] == bridge.LOKI


@pytest.mark.parametrize('counts,expected', [([0], 1), ([1000, 0], 2), ([1000]*5, 5)])
def test_log_catchup_is_bounded_and_stops_on_no_progress(counts, expected):
    with patch.object(bridge, 'prepare_traces'), patch.object(bridge, 'send_traces'), \
         patch.object(bridge, 'reconcile_traces') as reconcile, \
         patch.object(bridge, 'send_logs', side_effect=counts) as send:
        bridge.cycle(MagicMock())
    assert send.call_count == expected
    reconcile.assert_called_once()

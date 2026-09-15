#!/usr/bin/env python3
"""Exercise real HTTP/queue processing and verify the exported traces in Tempo."""
import sys
sys.dont_write_bytecode = True
import concurrent.futures
import datetime
import json
import pathlib
import secrets
import time
import urllib.parse
import urllib.request
import urllib.error

ROOT = pathlib.Path(__file__).resolve().parents[1]
STATE = ROOT / '.bootstrap' / 'observability'
APP = 'http://127.0.0.1:18081'
TEMPO = 'http://127.0.0.1:3200'
START_TIME = int(time.time()) - 1
RUN = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S') + '-' + secrets.token_hex(2)


def get(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers={"Accept": "application/json"}), timeout=20) as response:
        return json.load(response)


def xml(trade_id, message_id, bank1, bank2, currency='GBP', blank=False):
    return f'''<tradeMessage><header><messageId>{message_id}</messageId>
      <creationTimestamp>2026-09-15T10:00:00Z</creationTimestamp></header><trade>
      <tradeId>{'' if blank else trade_id}</tradeId><tradeType>SPOT</tradeType>
      <party1><partyId>{bank1}</partyId><role>BUYER</role></party1>
      <party2><partyId>{bank2}</partyId><role>SELLER</role></party2>
      <currencyPair><currency1>USD</currency1><amount1>500000</amount1>
      <currency2>{currency}</currency2><amount2>395000</amount2><exchangeRate>1.2658228</exchangeRate></currencyPair>
      <valueDate>2026-12-01</valueDate></trade></tradeMessage>'''


def submit(name, prefix='DEMO', bank1=None, bank2=None, currency='GBP', blank=False, malformed=False):
    trade_id = f'{prefix}-{RUN}-{name}'
    message_id = f'MSG-{RUN}-{name}'
    trace_id = None
    payload = xml(trade_id, message_id, bank1 or f'{name}-A-{RUN}', bank2 or f'{name}-B-{RUN}', currency, blank)
    if malformed:
        payload = f'<tradeMessage><header><messageId>{message_id}</messageId></header><trade><tradeId>{trade_id}</tradeId><unclosed>'
    request = urllib.request.Request(APP + '/api/trades', data=payload.encode(), headers={
        'Content-Type': 'application/xml'})
    with urllib.request.urlopen(request, timeout=20) as response:
        assert response.status == 202, response.status
        admission = json.load(response)
    return {'queueMessageId':int(admission['queueMessageId']), 'operationId':admission['operationId'], 'scenario': name, 'tradeId': trade_id, 'messageId': message_id, 'traceId': trace_id}


def wait_for(check, description, timeout=60):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        result = check()
        if result:
            return result
        time.sleep(0.5)
    raise AssertionError('Timed out: ' + description)


def spans(trace):
    for batch in trace.get('batches', trace.get('resourceSpans', [])):
        for scope in batch.get('scopeSpans', batch.get('instrumentationLibrarySpans', [])):
            yield from scope.get('spans', [])


def attrs(span):
    result = {}
    for attr in span.get('attributes', []):
        value = attr['value']
        result[attr['key']] = next(iter(value.values()), None)
    return result


def search(query):
    return get(TEMPO + '/api/search?' + urllib.parse.urlencode({'q': query, 'limit': 100, 'start': START_TIME, 'end': int(time.time()) + 1}))


def sql(query):
    req = urllib.request.Request('http://localhost:3300/api/ds/query', data=json.dumps({'from':'now-1h','to':'now','queries':[{'refId':'A','datasource':{'uid':'mocknet-operations','type':'grafana-postgresql-datasource'},'rawSql':query,'format':'table'}]}).encode(), headers={'Content-Type':'application/json'})
    with urllib.request.urlopen(req,timeout=20) as response: result=json.load(response)['results']['A']
    assert not result.get('error'), result
    return [dict(zip([f['name'] for f in frame['schema']['fields']], row)) for frame in result.get('frames',[]) for row in zip(*frame['data']['values'])]


def main():
    STATE.mkdir(parents=True, exist_ok=True)
    rows = []
    bank_a, bank_b = f'PAIR-A-{RUN}', f'PAIR-B-{RUN}'
    rows.append(submit('matched-buy', bank1=bank_a, bank2=bank_b))
    # Let the first trade reach matching so the pair is deterministic.
    first = wait_for(lambda: next((t for t in get(APP + '/api/trades') if t['tradeId'] == rows[0]['tradeId']), None), 'first trade ingestion')
    wait_for(lambda: sql("SELECT id FROM support.queue_messages WHERE stage='MATCHING' AND status='DONE' AND operation_id='"+rows[0]['operationId']+"'"), 'first trade unmatched processing')
    rows.append(submit('matched-sell', bank1=bank_b, bank2=bank_a))
    rows.append(submit('rejected', currency='XXX'))
    rows.append(submit('malformed', malformed=True))
    rows.append(submit('missing-id', blank=True))
    rows.append(submit('unmatched'))
    rows.append(submit('slow', prefix='DEMO-SLOW'))
    rows.append(submit('retry-recovery', prefix='DEMO-RETRY-RECOVER'))
    rows.append(submit('retry-exhaustion', prefix='DEMO-RETRY-EXHAUST'))
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        rows.extend(pool.map(lambda i: submit(f'concurrent-{i}'), range(6)))
    (STATE / 'demo-submissions.json').write_text(json.dumps({'runId': RUN, 'scenarios': rows}, indent=2) + '\n')
    # Each run has unique business IDs and can be repeated without clearing the database.
    expected = {'matched-buy': 'NETTED', 'matched-sell': 'NETTED', 'rejected': 'REJECTED',
                'unmatched': 'VALIDATED', 'slow': 'VALIDATED', 'retry-recovery': 'VALIDATED'}
    def terminal():
        trades = {t['tradeId']: t for t in get(APP + '/api/trades')}
        for row in rows:
            name = row['scenario']
            if name in expected and trades.get(row['tradeId'], {}).get('status') != expected[name]:
                return False
        current = sql('SELECT id,status,failed_attempts AS attempts,trace_id FROM support.queue_messages WHERE id IN ('+','.join(str(r['queueMessageId']) for r in rows)+')')
        return current if len(current) == len(rows) and all(m['status'] in ('DONE', 'FAILED') for m in current) else False
    messages = wait_for(terminal, 'all scenario business outcomes')
    for row in rows:
        message = next(m for m in messages if row['queueMessageId'] == m['id'])
        row['traceId'] = message['trace_id']
        row['queueStatus'] = message['status']
        row['attempts'] = message['attempts']
        if row['scenario'] == 'retry-recovery': assert message['status'] == 'DONE' and message['attempts'] == 2
        if row['scenario'] == 'retry-exhaustion': assert message['status'] == 'FAILED' and message['attempts'] == 3
        if row['scenario'] in ('malformed', 'missing-id'): assert message['status'] == 'FAILED'
        # Wait for the async consumer spans, which can arrive after the HTTP span.
        def exported():
            try:
                trace = get(TEMPO + '/api/traces/' + row['traceId'])
            except urllib.error.HTTPError as error:
                if error.code == 404: return False
                raise
            found = [s for s in spans(trace) if s['name'] == 'QueueMessage.process']
            needed = 3 if row['scenario'].startswith('retry-') else 1
            return trace if len(found) >= needed else False
        trace = wait_for(exported, 'consumer spans ' + row['scenario'])
        (STATE / f'trace-{row["scenario"]}.json').write_text(json.dumps(trace, indent=2) + '\n')
        processing = [s for s in spans(trace) if s['name'] == 'QueueMessage.process']
        row['processing'] = [{'queue': attrs(s).get('queue.name'), 'outcome': attrs(s).get('processing.outcome'),
            'attempt': attrs(s).get('queue.attempt'), 'reason': attrs(s).get('failure.reason_code'),
            'durationMs': (int(s['endTimeUnixNano']) - int(s['startTimeUnixNano'])) / 1e6} for s in processing]
        row['spanCount'] = len(list(spans(trace)))
        assert any(s['name'] == 'POST /api/trades' and not s.get('parentSpanId') for s in spans(trace)), 'Missing HTTP root'
        if row['scenario'] == 'matched-sell':
            assert any(s['name'] == 'TwoPhaseCommitCoordinator.executeTransaction' for s in spans(trace)), 'Missing settlement span'
        outcomes = {p['outcome'] for p in row['processing']}
        if row['scenario'] == 'rejected': assert 'rejected' in outcomes
        if row['scenario'].startswith('retry-'): assert 'retried' in outcomes
        if row['scenario'] == 'retry-exhaustion': assert 'failed' in outcomes
        if row['scenario'] == 'slow': assert any(p['durationMs'] >= 300 for p in row['processing'])
    query_checks = {}
    for name, query in {
        'errors': '{ resource.service.name = "mocknet" && status = error }',
        'rejections': '{ span.processing.outcome = "rejected" }',
        'retries': '{ span.processing.outcome = "retried" }',
        'slow': '{ name = "QueueMessage.process" && duration > 250ms }',
        'settlement': '{ span.component.stage = "SETTLEMENT" }',
        'business-id': '{ span.trade.id = "' + rows[0]['tradeId'] + '" }',
    }.items():
        expected_names = {'errors': ['malformed', 'missing-id', 'retry-recovery', 'retry-exhaustion'], 'rejections': ['rejected'], 'retries': ['retry-recovery', 'retry-exhaustion'], 'slow': ['slow'], 'settlement': ['matched-sell'], 'business-id': ['matched-buy']}[name]
        expected_ids = {row['traceId'] for row in rows if row['scenario'] in expected_names}
        def indexed():
            result = search(query)
            found = {t['traceID'].zfill(32) for t in result.get('traces', [])}
            return result if expected_ids <= found else False
        result = wait_for(indexed, 'search index ' + name)
        query_checks[name] = {'query': query, 'matches': len(result['traces'])}
    report = {'runId': RUN, 'scenarios': rows, 'queries': query_checks, 'status': get(APP + '/api/status')}
    (STATE / 'demo-results.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))

if __name__ == '__main__':
    main()

"""Export C evidence to Tempo/Loki without instrumenting the JVM.

One immutable settled-operation snapshot per trace. Delivery is tracked before IO.
An ambiguous response is quarantined, never blindly resent into TraceQL metrics.
The reporting store remains authoritative and keeps subsequent/unfinished evidence.
"""
import datetime as dt
import hashlib
import json
import os
import socket
import time
import urllib.error
import urllib.request
import uuid
from decimal import Decimal

from psycopg.types.json import Jsonb

TEMPO = 'http://127.0.0.1:24318/v1/traces'
LOKI = 'http://127.0.0.1:13102/loki/api/v1/push'
ENVIRONMENT = os.environ.get('MOCKNET_C_ENVIRONMENT', 'local-demo')
LOG_BATCH_SIZE = 1000
LOG_BATCHES_PER_CYCLE = 5
LOG_BATCH_QUERY = """WITH pending AS MATERIALIZED (
    SELECT event_id,event_at FROM c_log_exports WHERE state='pending'
    ORDER BY event_at,event_id LIMIT %s
)
SELECT e.event_id,e.source,e.at,coalesce(e.operation_id,paired.operation_id),e.body
FROM pending x JOIN evidence e USING(event_id)
LEFT JOIN LATERAL (SELECT f.operation_id FROM evidence f WHERE e.source='component_log'
  AND e.operation_id IS NULL AND f.source='component_log' AND f.kind='end'
  AND f.entity_id=e.entity_id AND f.operation_id IS NOT NULL LIMIT 1) paired ON true
WHERE e.observed_at<now()-interval '5 seconds'
ORDER BY x.event_at,x.event_id"""


def trace_id(operation):
    return hashlib.md5(('c-reconstruction:' + operation).encode()).hexdigest()


def attributes(values):
    result = []
    for key, value in sorted(values.items()):
        if value is None:
            continue
        if isinstance(value, bool):
            encoded = {'boolValue': value}
        elif isinstance(value, int):
            encoded = {'intValue': str(value)}
        elif isinstance(value, float):
            encoded = {'doubleValue': value}
        else:
            encoded = {'stringValue': value if isinstance(value, str) else json.dumps(value, sort_keys=True)}
        result.append({'key': key, 'value': encoded})
    return result


def trace_payload(rows, disposition):
    """Use the SQL renderer's stable identities/parents; stages are attributes, not services."""
    ids = {r['spanID'] for r in rows}
    if not rows or len(ids) != len(rows) or sum(not r['parentSpanID'] for r in rows) != 1:
        raise ValueError('invalid span identities or root count')
    spans = []
    for row in rows:
        if row['parentSpanID'] and row['parentSpanID'] not in ids:
            raise ValueError('unresolved parent')
        if row['duration'] < 0 or row['startTime'] <= 0:
            raise ValueError('invalid interval')
        tags = {t['key']: t['value'] for t in row['tags']}
        tags.update({'evidence.reconstructed': True, 'evidence.snapshot': 'settled operation',
                     'component.stage': tags.get('component.stage', row['serviceName']), 'evidence.warnings': row['warnings']})
        root = not row['parentSpanID']
        if root:
            tags.update({'business.disposition': disposition, 'evidence.kind': 'operation grouping',
                         'latency.scope': 'operation evidence envelope, not HTTP latency'})
        error = disposition == 'failed' if root else tags.get('error') is True
        start = int(Decimal(str(row['startTime'])) * 1_000_000)
        span = {'traceId': row['traceID'], 'spanId': row['spanID'],
                'name': row['operationName'], 'kind': 1,
                'startTimeUnixNano': str(start),
                'endTimeUnixNano': str(start + int(Decimal(str(row['duration'])) * 1_000_000)),
                'attributes': attributes(tags), 'status': {'code': 2 if error else 0}}
        if row['parentSpanID']:
            span['parentSpanId'] = row['parentSpanID']
        spans.append(span)
    return {'resourceSpans': [{'resource': {'attributes': attributes({
        'service.name': 'mocknet', 'deployment.environment.name': ENVIRONMENT,
        'observability.approach': 'C', 'evidence.reconstructed': True})},
        'scopeSpans': [{'scope': {'name': 'cit.c.reconstruction', 'version': '1'}, 'spans': spans}]}]}


def log_payload(rows):
    streams = {}
    for event_id, source, at, operation, body in rows:
        labels = {'service_name': 'mocknet', 'deployment_environment_name': ENVIRONMENT,
                  'source': source, 'level': body.get('level') or ('ERROR' if body.get('outcome') in ('exception','failed') else 'WARN' if body.get('outcome') in ('retried','abandoned') else 'INFO')}
        key = tuple(sorted(labels.items()))
        record = dict(body, event_id=event_id, source=source)
        metadata = {'event_id': event_id}
        if operation:
            record.update(operation_id=operation, trace_id=trace_id(operation))
            metadata.update(operation_id=operation, trace_id=trace_id(operation))
        if body.get('stage'):
            metadata['stage'] = str(body['stage'])
        # datetime retains microseconds, unlike floating-point timestamp arithmetic.
        epoch = at - dt.datetime(1970, 1, 1, tzinfo=dt.timezone.utc)
        stamp = str((epoch.days * 86400 + epoch.seconds) * 1_000_000_000 + epoch.microseconds * 1000)
        streams.setdefault(key, []).append([stamp, json.dumps(record, sort_keys=True, separators=(',', ':')), metadata])
    return {'streams': [{'stream': dict(key), 'values': sorted(values, key=lambda v: int(v[0]))}
                        for key, values in streams.items()]}


def post(url, payload):
    """Only a refused connection proves no request was sent. Timeouts are ambiguous."""
    request = urllib.request.Request(url, data=json.dumps(payload).encode(), headers={'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            raw = response.read()
            if url == TEMPO and raw:
                result = json.loads(raw)
                if int(result.get('partialSuccess', {}).get('rejectedSpans', 0)):
                    return 'uncertain', 'partial acceptance'
            return 'exported', None
    except urllib.error.HTTPError as error:
        return ('rejected' if error.code in (400, 401, 403, 404, 413) else 'uncertain'), 'HTTP ' + str(error.code)
    except urllib.error.URLError as error:
        if isinstance(error.reason, ConnectionRefusedError):
            return 'pending', 'connection refused before delivery'
        return 'uncertain', type(error.reason).__name__
    except (TimeoutError, socket.timeout, OSError, ValueError) as error:
        return 'uncertain', type(error).__name__


def prepare_traces(db):
    candidates = db.execute("""SELECT a.operation_id,a.version
        FROM c_trace_activity a LEFT JOIN c_trace_exports e USING(operation_id)
        WHERE e.operation_id IS NULL AND a.changed_at<now()-interval '15 seconds'
        ORDER BY a.checked_at NULLS FIRST,a.changed_at DESC LIMIT 10""").fetchall()
    healthy = db.execute("SELECT EXISTS(SELECT 1 FROM collector_status WHERE id=1 AND at>now()-interval '10 seconds' AND pending_journal=0)").fetchone()[0]
    if not healthy:
        return
    for operation, version in candidates:
        db.execute('UPDATE c_trace_activity SET checked_at=now() WHERE operation_id=%s', (operation,))
        summary = db.execute('SELECT unfinished,disposition FROM c_operations WHERE operation_id=%s', (operation,)).fetchone()
        if not summary or summary[0]:
            continue
        if db.execute('SELECT EXISTS(SELECT 1 FROM c_calls_for(%s) WHERE finished_at IS NULL)', (operation,)).fetchone()[0]:
            continue
        disposition = summary[1]
        with db.transaction():
            # Lock against concurrent evidence-trigger updates while freezing this version.
            current = db.execute('SELECT version FROM c_trace_activity WHERE operation_id=%s FOR UPDATE', (operation,)).fetchone()[0]
            if current != version:
                continue
            cursor = db.execute('SELECT * FROM c_waterfall(%s)', (operation,))
            names = [c.name for c in cursor.description]
            rows = [dict(zip(names, row)) for row in cursor.fetchall()]
            payload = trace_payload(rows, disposition)
            db.execute("""INSERT INTO c_trace_exports(operation_id,trace_id,source_version,state,payload,span_ids)
                VALUES(%s,%s,%s,'pending',%s,%s) ON CONFLICT DO NOTHING""",
                (operation, trace_id(operation), version, Jsonb(payload), Jsonb([r['spanID'] for r in rows])))


def backend_ready(url):
    try:
        with urllib.request.urlopen(url, timeout=3) as response:
            return response.status == 200
    except OSError:
        return False


def send_traces(db):
    if not backend_ready('http://127.0.0.1:3202/ready'):
        return
    for operation, payload in db.execute("SELECT operation_id,payload FROM c_trace_exports WHERE state='pending' ORDER BY created_at LIMIT 10").fetchall():
        db.execute("UPDATE c_trace_exports SET state='sending',error_code=NULL WHERE operation_id=%s", (operation,))
        state, error = post(TEMPO, payload)
        db.execute("UPDATE c_trace_exports SET state=%s,sent_at=now(),error_code=%s,payload=CASE WHEN %s='exported' THEN NULL ELSE payload END WHERE operation_id=%s",
                   (state, error, state, operation))
        if state == 'pending':
            break


def send_logs(db):
    if not backend_ready('http://127.0.0.1:13102/ready'):
        return 0
    rows = db.execute(LOG_BATCH_QUERY, (LOG_BATCH_SIZE,)).fetchall()
    if not rows:
        return 0
    batch = str(uuid.uuid4())
    event_ids = [r[0] for r in rows]
    db.execute("UPDATE c_log_exports SET state='sending',batch_id=%s WHERE event_id=ANY(%s)", (batch, event_ids))
    state, error = post(LOKI, log_payload(rows))
    db.execute('UPDATE c_log_exports SET state=%s,sent_at=now(),error_code=%s WHERE event_id=ANY(%s) AND batch_id=%s',
               (state, error, event_ids, batch))
    # Do not hot-loop against an unavailable or rejecting backend.
    return len(rows) if state == 'exported' else 0


def recover(db):
    # A crash between remote acceptance and local commit must not duplicate delivery.
    db.execute("UPDATE c_trace_exports SET state='uncertain',error_code='interrupted delivery' WHERE state='sending'")
    db.execute("UPDATE c_log_exports SET state='uncertain',error_code='interrupted delivery' WHERE state='sending'")


def reconcile_traces(db):
    for operation, trace, expected in db.execute("SELECT operation_id,trace_id,span_ids FROM c_trace_exports WHERE state='uncertain' ORDER BY sent_at LIMIT 5").fetchall():
        try:
            request = urllib.request.Request('http://127.0.0.1:3202/api/traces/' + trace, headers={'Accept': 'application/json'})
            with urllib.request.urlopen(request, timeout=5) as response:
                data = json.load(response)
            found = {span['spanId'] for batch in data.get('batches', data.get('resourceSpans', []))
                     for scope in batch.get('scopeSpans', []) for span in scope.get('spans', [])}
            # Tempo JSON encodes byte IDs as base64. Normalize for exact comparison.
            import base64
            normalized = {base64.b64decode(s).hex() if len(s) != 16 else s for s in found}
            if set(expected) <= normalized:
                db.execute("UPDATE c_trace_exports SET state='exported',error_code=NULL,payload=NULL WHERE operation_id=%s", (operation,))
        except (OSError, ValueError):
            pass


def cycle(db):
    prepare_traces(db)
    send_traces(db)
    for _ in range(LOG_BATCHES_PER_CYCLE):
        if send_logs(db) < LOG_BATCH_SIZE:
            break
    reconcile_traces(db)
    db.execute("INSERT INTO c_telemetry_status(id,at) VALUES(1,now()) ON CONFLICT(id) DO UPDATE SET at=excluded.at,error_code=NULL")

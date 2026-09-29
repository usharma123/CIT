#!/usr/bin/env python3
"""Admit one bounded local trade workload and record observed business outcomes."""

import argparse
import datetime as dt
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from xml.sax.saxutils import escape
from mocknet_c_demo import latest_seed, pin_links

ROOT = Path(__file__).resolve().parents[1]
STATE = ROOT / '.bootstrap/observability/approach-c'
APP = 'http://127.0.0.1:18101'
GRAFANA = 'http://localhost:3302'


def require_local_demo(mode, command):
    if mode != 'local-demo' or '--spring.profiles.active=observability-demo' not in command:
        raise RuntimeError('Seed requires the local-demo C JVM profile on 127.0.0.1:18101')


def check_target():
    pid = int((STATE / 'app.pid').read_text().strip())
    command = subprocess.check_output(['ps', '-p', str(pid), '-o', 'command='], text=True).strip()
    require_local_demo(os.environ.get('MOCKNET_C_MODE', 'local-demo'), command)
    if str(STATE / 'app.jar') not in command:
        raise RuntimeError('C JVM PID does not belong to this workspace')
    with urllib.request.urlopen(APP + '/api/status', timeout=5) as response:
        if response.status != 200:
            raise RuntimeError('Local C application is unhealthy')


def value_date():
    day = dt.datetime.now(dt.timezone.utc).date()
    for _ in range(2):
        day += dt.timedelta(days=1)
        while day.weekday() >= 5:
            day += dt.timedelta(days=1)
    return day.isoformat()


def trade_xml(trade_id, message_id, party_a, party_b, currency='GBP'):
    created = dt.datetime.now(dt.timezone.utc).isoformat(timespec='seconds').replace('+00:00', 'Z')
    fields = [trade_id, message_id, party_a, party_b, currency]
    if any(not value or len(value) > 120 for value in fields):
        raise ValueError('Trade fields must be non-empty and at most 120 characters')
    trade_id, message_id, party_a, party_b, currency = map(escape, fields)
    return (f'<tradeMessage><header><messageId>{message_id}</messageId><creationTimestamp>{created}</creationTimestamp></header>'
            f'<trade><tradeId>{trade_id}</tradeId><tradeType>SPOT</tradeType>'
            f'<party1><partyId>{party_a}</partyId><role>BUYER</role></party1>'
            f'<party2><partyId>{party_b}</partyId><role>SELLER</role></party2>'
            f'<currencyPair><currency1>USD</currency1><amount1>500000</amount1>'
            f'<currency2>{currency}</currency2><amount2>395000</amount2>'
            f'<exchangeRate>1.2658228</exchangeRate></currencyPair>'
            f'<valueDate>{value_date()}</valueDate></trade></tradeMessage>')


def scenarios(run_id, healthy_pairs=40):
    if not 0 <= healthy_pairs <= 60:
        raise ValueError('--healthy-pairs must be between 0 and 60')

    def item(label, name, pair=None, reverse=False, delay=0, currency='GBP', prefix='BIZ', expected='Instructions ready'):
        trade_id = f'{prefix}-{run_id}-{name}'
        party_a = f'ALPHA-{run_id}-{pair or name}'
        party_b = f'BETA-{run_id}-{pair or name}'
        return dict(label=label, tradeId=trade_id, messageId=f'MSG-{trade_id}',
                    partyA=party_b if reverse else party_a,
                    partyB=party_a if reverse else party_b,
                    currency=currency, delaySeconds=delay, expectedState=expected)
    planned = [
        item('Healthy pair, first leg', 'HEALTHY-1', pair='HEALTHY'),
        item('Waiting for counterparty', 'AWAITING', expected='Waiting for counterparty'),
        item('Matching SLA breach, first leg', 'MATCH-LATE-1', pair='MATCH-LATE'),
        item('Instruction SLA breach, first leg', 'INSTRUCTION-LATE-1', pair='INSTRUCTION-LATE'),
        item('Rejected trade', 'REJECTED', currency='XXX', expected='Rejected'),
        item('Retry recovered, first leg', 'RECOVER', pair='RECOVER', prefix='DEMO-RETRY-RECOVER'),
        item('Retries exhausted', 'EXHAUST', prefix='DEMO-RETRY-EXHAUST', expected='Failed'),
        item('Healthy pair, second leg', 'HEALTHY-2', pair='HEALTHY', reverse=True, delay=1),
        item('Retry recovered, second leg', 'RECOVER-2', pair='RECOVER', reverse=True, delay=5),
        item('Matching SLA breach, second leg', 'MATCH-LATE-2', pair='MATCH-LATE', reverse=True, delay=34),
        item('Instruction SLA breach, second leg', 'INSTRUCTION-LATE-2', pair='INSTRUCTION-LATE', reverse=True, delay=64),
    ]
    for number in range(healthy_pairs):
        name = f'ROUTINE-{number + 1:02d}'
        delay = 1.5 + number * (60 / max(healthy_pairs, 1))
        planned.append(item(f'Routine pair {number + 1:02d}, first leg', name + '-1',
                            pair=name, delay=delay))
        planned.append(item(f'Routine pair {number + 1:02d}, second leg', name + '-2',
                            pair=name, reverse=True, delay=delay + 0.4))
    return sorted(planned, key=lambda row: row['delaySeconds'])


def save_manifest(path, manifest):
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(manifest, indent=2, sort_keys=True) + '\n')
    temporary.replace(path)


def admit(row, from_ms):
    body = trade_xml(row['tradeId'], row['messageId'], row['partyA'], row['partyB'], row['currency'])
    request = urllib.request.Request(APP + '/api/trades', data=body.encode(),
                                     headers={'Content-Type': 'application/xml'}, method='POST')
    # An uncertain POST is never retried. Its unique IDs permit later investigation.
    with urllib.request.urlopen(request, timeout=10) as response:
        if response.status != 202:
            raise RuntimeError(f'Unexpected admission status: {response.status}')
        result = json.load(response)
    row['admittedAt'] = dt.datetime.now(dt.timezone.utc).isoformat()
    row['httpStatus'] = response.status
    row['queueMessageId'] = result['queueMessageId']
    row['operationId'] = result['operationId']
    row['processUrl'] = (GRAFANA + '/d/mocknet-c-process?'
                         + urllib.parse.urlencode({'var-operation': row['operationId'], 'from': from_ms, 'to': 'now'}))


def read_outcomes(rows):
    import psycopg

    secret_file = ROOT / '.bootstrap/observability/grafana.env'
    passwords = dict(line.split('=', 1) for line in secret_file.read_text().splitlines() if '=' in line)
    operation_ids = [row['operationId'] for row in rows if row.get('operationId')]
    with psycopg.connect(host='127.0.0.1', port=15452, dbname='telemetry_c', user='grafana_c',
                         password=passwords['MOCKNET_READER_PASSWORD'], connect_timeout=3) as connection:
        with connection.cursor() as cursor:
            return collect_sla(cursor, operation_ids)


def collect_sla(cursor, operation_ids):
    # One pass preserves all three policy outcomes per operation.
    cursor.execute('''SELECT operation_id, business_state, retries, policy_id, sla_status,
                             round(elapsed_seconds::numeric, 1), target_seconds
                      FROM c_process_sla WHERE operation_id = ANY(%s)''', (operation_ids,))
    outcomes = {}
    for op, state, retries, policy, status, elapsed, target in cursor.fetchall():
        record = outcomes.setdefault(op, {'businessState': state, 'retries': retries, 'sla': {}})
        record['sla'][policy] = {'status': status, 'elapsedSeconds': float(elapsed) if elapsed is not None else None,
                                 'targetSeconds': target}
    return outcomes


def expected_ready(rows, outcomes):
    if not all(outcomes.get(row.get('operationId'), {}).get('businessState') == row['expectedState'] for row in rows):
        return False
    by_label = {row['label']: outcomes[row['operationId']] for row in rows}
    return (by_label['Retry recovered, first leg']['retries'] >= 2
            and by_label['Matching SLA breach, first leg']['sla']['matching']['status'] == 'Breached'
            and by_label['Instruction SLA breach, first leg']['sla']['instructions']['status'] == 'Breached'
            and by_label['Healthy pair, first leg']['sla']['instructions']['status'] == 'Met')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--healthy-pairs', type=int, default=40, help='additional matched pairs, 0-60 (default: 40)')
    parser.add_argument('--new-run', action='store_true', help='explicitly admit another batch instead of reusing the completed seed')
    args = parser.parse_args()
    if os.environ.get('MOCKNET_C_MODE', 'local-demo') != 'local-demo':
        raise RuntimeError('Seed requires local-demo mode')
    existing = latest_seed(STATE)
    if existing and not args.new_run:
        path, manifest = existing
        pin_links(manifest)
        save_manifest(path, manifest)
        subprocess.run([sys.executable, str(ROOT / 'script/build-mocknet-approach-c.py')], check=True)
        print(f'Reusing seed: {path}')
        print(f"Dashboard: {manifest['dashboardUrl']}")
        return
    check_target()
    run_id = dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%S') + '-' + secrets.token_hex(3).upper()
    STATE.joinpath('seeds').mkdir(parents=True, exist_ok=True)
    path = STATE / 'seeds' / f'{run_id}.json'
    path.touch(exist_ok=False)
    started = time.monotonic()
    from_ms = int((time.time() - 120) * 1000)
    rows = scenarios(run_id, args.healthy_pairs)
    manifest = {'runId': run_id, 'startedAt': dt.datetime.now(dt.timezone.utc).isoformat(),
                'destination': APP, 'mode': 'local-demo', 'state': 'admitting',
                'dashboardUrl': GRAFANA + '/d/mocknet-c-business?'
                                + urllib.parse.urlencode({'from': from_ms, 'to': 'now'}),
                'scenarios': rows}
    save_manifest(path, manifest)
    try:
        for row in rows:
            remaining = row['delaySeconds'] - (time.monotonic() - started)
            if remaining > 0:
                time.sleep(remaining)
            admit(row, from_ms)
            print(f"{row['label']}: {row['operationId']}", flush=True)
            save_manifest(path, manifest)
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            outcomes = read_outcomes(rows)
            for row in rows:
                row['observed'] = outcomes.get(row.get('operationId'))
            if expected_ready(rows, outcomes):
                manifest['state'] = 'verified'
                break
            time.sleep(1)
        else:
            manifest['state'] = 'incomplete'
        manifest['finishedAt'] = dt.datetime.now(dt.timezone.utc).isoformat()
        pin_links(manifest)
        save_manifest(path, manifest)
        print(f'Manifest: {path}')
        print(f"Dashboard: {manifest['dashboardUrl']}")
        if manifest['state'] != 'verified':
            raise RuntimeError('Some business outcomes are not yet verified; inspect manifest')
        subprocess.run([sys.executable, str(ROOT / 'script/build-mocknet-approach-c.py')], check=True)
    except Exception:
        manifest['state'] = 'incomplete'
        manifest['finishedAt'] = dt.datetime.now(dt.timezone.utc).isoformat()
        save_manifest(path, manifest)
        raise


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, RuntimeError) as error:
        sys.exit(str(error))

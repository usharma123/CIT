#!/usr/bin/env python3
"""Manage one continuous, rate-limited HTTP workload for the local Mocknet demo."""
import sys
sys.dont_write_bytecode = True
import argparse
import collections
import datetime
import fcntl
import http.client
import importlib.util
import json
import os
from pathlib import Path
import secrets
import signal
import subprocess
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
STATE = ROOT / '.bootstrap/observability'
PID_FILE = STATE / 'stream.pid'
STATUS_FILE = STATE / 'stream-status.json'
SCRIPT = Path(__file__).resolve()
APP = 'http://127.0.0.1:18081'
RUNNING = True


def process_running():
    try:
        pid = int(PID_FILE.read_text())
        command = subprocess.check_output(['ps', '-p', str(pid), '-o', 'command='], text=True, stderr=subprocess.DEVNULL)
        actual_approach = 'C' if '--approach C' in command else 'B' if '--approach B' in command else 'A'
        expected_approach = 'C' if APP.endswith(':18101') else 'B' if APP.endswith(':18091') else 'A'
        same_approach = actual_approach == expected_approach
        return pid if str(SCRIPT) in command and ' run ' in command and same_approach else None
    except (OSError, ValueError, subprocess.CalledProcessError):
        return None


def write_status(status):
    temporary = STATUS_FILE.with_suffix('.tmp')
    temporary.write_text(json.dumps(status, indent=2) + '\n')
    temporary.replace(STATUS_FILE)


def get(path):
    with urllib.request.urlopen(APP + path, timeout=10) as response:
        return json.load(response)


def run(rate):
    global RUNNING
    # Lock covers the workload, so concurrent starts cannot create two feeders.
    lock = (STATE / 'stream.lock').open('a')
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        return
    PID_FILE.write_text(str(os.getpid()))
    spec = importlib.util.spec_from_file_location('demo', ROOT / 'script/mocknet-grafana-demo.py')
    demo = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(demo)
    run_id = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S') + '-' + secrets.token_hex(2)
    status = {'pid': os.getpid(), 'runId': run_id, 'startedAt': time.time(), 'requestsPerSecond': rate,
              'state': 'starting', 'accepted': 0, 'httpFailures': 0, 'scenarioCounts': {}, 'recent': []}
    recent = collections.deque(maxlen=30)
    counts = collections.Counter()
    def stop(_signal, _frame):
        global RUNNING
        RUNNING = False
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    next_request = time.monotonic()
    sequence = 0
    next_check = 0
    try:
        while RUNNING:
            if time.monotonic() >= next_check:
                try:
                    health = get('/api/status')
                    backlog = sum(v['NEW'] + v['PROCESSING'] for k, v in health['queues'].items() if k != 'DEAD_LETTER')
                    total = sum(health['queues']['INGESTION'].values())
                    free = os.statvfs(STATE).f_bavail * os.statvfs(STATE).f_frsize
                    pause = 'backlog limit (1000)' if backlog >= 1000 else 'retained admission limit (100000)' if total >= 100000 else 'local free space below 2 GiB' if free < 2 * 1024**3 else None
                    status.update(backlog=backlog, totalAdmissions=total, pauseReason=pause)
                    if pause:
                        status.update(state='paused', updatedAt=time.time()); write_status(status)
                        time.sleep(5)
                        continue
                    status.update(state='running', pauseReason=None)
                    next_check = time.monotonic() + 10
                except (OSError, http.client.HTTPException, ValueError) as error:
                    status.update(state='waiting_for_backend', pauseReason=str(error), updatedAt=time.time()); write_status(status)
                    time.sleep(5)
                    continue
            cycle, slot = divmod(sequence, 10)
            scenario = 'matched' if slot < 6 else {6:'unmatched', 7:'slow', 8:'retry-recovery', 9:['rejected','retry-exhaustion','malformed'][cycle % 3]}[slot]
            prefix = {'slow':'DEMO-SLOW','retry-recovery':'DEMO-RETRY-RECOVER','retry-exhaustion':'DEMO-RETRY-EXHAUST'}.get(scenario, 'STREAM')
            trade = f'{prefix}-{run_id}-{sequence:07d}'
            message = f'MSG-{run_id}-{sequence:07d}'
            if slot < 6:
                pair = f'{run_id}-{cycle}-{slot//2}'
                a, b = f'A-{pair}', f'B-{pair}'
                if slot % 2: a, b = b, a
            else:
                a, b = f'A-{run_id}-{sequence}', f'B-{run_id}-{sequence}'
            payload = demo.xml(trade, message, a, b, currency='XXX' if scenario == 'rejected' else 'GBP')
            if scenario == 'malformed':
                payload = f'<tradeMessage><header><messageId>{message}</messageId></header><trade><tradeId>{trade}</tradeId><unclosed>'
            row = {'at': time.time(), 'scenario': scenario, 'tradeId': trade}
            try:
                request = urllib.request.Request(APP + '/api/trades', data=payload.encode(), headers={'Content-Type':'application/xml'})
                with urllib.request.urlopen(request, timeout=10) as response:
                    row.update(json.load(response), httpStatus=response.status)
                if row['httpStatus'] == 202:
                    status['accepted'] += 1
                    counts[scenario] += 1
                else:
                    status['httpFailures'] += 1
            except (OSError, http.client.HTTPException, ValueError) as error:
                # Do not repeat an uncertain POST: it may already have been admitted.
                status['httpFailures'] += 1
                row.update(error=str(error))
                next_check = 0
            recent.append(row)
            status.update(updatedAt=time.time(), scenarioCounts=dict(counts), recent=list(recent))
            write_status(status)
            sequence += 1
            next_request = max(next_request + 1/rate, time.monotonic())
            while RUNNING and time.monotonic() < next_request:
                time.sleep(max(0, min(.2, next_request-time.monotonic())))
    finally:
        status.update(state='stopped', updatedAt=time.time()); write_status(status)
        if PID_FILE.exists() and PID_FILE.read_text().strip() == str(os.getpid()): PID_FILE.unlink()


def main():
    global STATE, PID_FILE, STATUS_FILE, APP
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['start','stop','status','run'])
    parser.add_argument('--rate', type=float, default=1.0)
    parser.add_argument('--approach', choices=['A','B','C'], default='A')
    args = parser.parse_args()
    if args.approach in ('B', 'C'):
        STATE = ROOT / ('.bootstrap/observability/approach-' + args.approach.lower())
        PID_FILE = STATE / 'stream.pid'
        STATUS_FILE = STATE / 'stream-status.json'
        APP = 'http://127.0.0.1:' + ('18091' if args.approach == 'B' else '18101')
    if not 0.1 <= args.rate <= 10: parser.error('--rate must be between 0.1 and 10 requests/second')
    STATE.mkdir(parents=True, exist_ok=True)
    if args.command == 'run': return run(args.rate)
    pid = process_running()
    if args.command == 'stop':
        if pid:
            os.kill(pid, signal.SIGTERM)
            deadline = time.monotonic() + 15
            while process_running() and time.monotonic() < deadline: time.sleep(.2)
            if process_running(): raise SystemExit('Seeder did not stop within 15 seconds')
        print('Transaction feed stopped')
    elif args.command == 'start':
        if not pid:
            get('/api/status')
            with (STATE / 'stream.log').open('a') as log:
                subprocess.Popen([sys.executable,str(SCRIPT),'run','--rate',str(args.rate),'--approach',args.approach],stdin=subprocess.DEVNULL,stdout=log,stderr=log,start_new_session=True,cwd=ROOT)
            deadline = time.monotonic() + 10
            while not process_running() and time.monotonic() < deadline: time.sleep(.1)
            pid = process_running()
            if not pid: raise SystemExit('Seeder failed to start; inspect .bootstrap/observability/stream.log')
        print(f'Transaction feed running, PID {pid}')
    else:
        status = json.loads(STATUS_FILE.read_text()) if STATUS_FILE.exists() else {}
        print(json.dumps({**status,'processRunning':bool(pid)},indent=2))

if __name__ == '__main__': main()

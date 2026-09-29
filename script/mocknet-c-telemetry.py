#!/usr/bin/env python3
"""Run C's independent reporting-to-Tempo/Loki worker."""
import argparse
import importlib.util
import json
import os
import pathlib
import signal
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]
STATE = ROOT / '.bootstrap/observability/approach-c'
SCRIPT = pathlib.Path(__file__).resolve()
sys.path.insert(0, str(ROOT / 'observability/approach-c/telemetry'))
import bridge
spec = importlib.util.spec_from_file_location('reporter', ROOT / 'script/mocknet-c-reporter.py')
reporter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reporter)
RUNNING = True


def stop(*_):
    global RUNNING
    RUNNING = False


def run():
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    db = None
    try:
        while RUNNING:
            try:
                if db is None:
                    db = reporter.connect('telemetry_c', 'c_reporter')
                    db.autocommit = True
                    db.execute("SET statement_timeout='20s'")
                    db.execute('SET max_parallel_workers_per_gather=0')
                    if not db.execute('SELECT pg_try_advisory_lock(67123002)').fetchone()[0]:
                        print('Another C telemetry worker owns the export lock', flush=True)
                        return
                    bridge.recover(db)
                    (STATE / 'telemetry.pid').write_text(str(os.getpid()))
                bridge.cycle(db)
                (STATE / 'telemetry-status.json').write_text(json.dumps({'at': time.time(), 'status': 'running'})+'\n')
            except Exception as error:
                print(json.dumps({'at': time.time(), 'error': type(error).__name__}), flush=True)
                if db is not None:
                    try:
                        db.execute("INSERT INTO c_telemetry_status(id,at,error_code) VALUES(1,now(),%s) ON CONFLICT(id) DO UPDATE SET at=excluded.at,error_code=excluded.error_code", (type(error).__name__,))
                    except Exception:
                        pass
                    db.close()
                db = None
                time.sleep(3)
            time.sleep(1)
    finally:
        if db is not None:
            db.close()
        path = STATE / 'telemetry.pid'
        if path.exists() and path.read_text().strip() == str(os.getpid()):
            path.unlink()


def owned_pid():
    path = STATE / 'telemetry.pid'
    if not path.exists():
        return None
    pid = int(path.read_text())
    command = subprocess.run(['ps', '-p', str(pid), '-o', 'command='], capture_output=True, text=True).stdout
    return pid if str(SCRIPT) + ' run' in command else None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['run', 'start', 'stop', 'status'])
    args = parser.parse_args()
    if args.command == 'run':
        return run()
    pid = owned_pid()
    if args.command == 'start' and not pid:
        with (STATE / 'telemetry.log').open('ab') as log:
            subprocess.Popen([sys.executable, str(SCRIPT), 'run'], stdin=subprocess.DEVNULL, stdout=log, stderr=log, start_new_session=True)
        for _ in range(100):
            if owned_pid():
                break
            time.sleep(.1)
        if not owned_pid():
            raise RuntimeError('Telemetry worker failed to start; inspect telemetry.log')
    elif args.command == 'stop' and pid:
        os.kill(pid, signal.SIGTERM)
        for _ in range(450):
            if not owned_pid():
                break
            time.sleep(.1)
        if owned_pid():
            raise RuntimeError('Telemetry worker has not stopped')
    print(json.dumps({'pid': owned_pid()}))


if __name__ == '__main__':
    main()

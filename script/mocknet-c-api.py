#!/usr/bin/env python3
"""Manage C's Gunicorn backend or invoke its authenticated diagnostic API."""
import argparse
import json
import os
import pathlib
import signal
import subprocess
import sys
import time
import urllib.request

ROOT=pathlib.Path(__file__).resolve().parents[1]
BACKEND=ROOT/'observability/approach-c/backend'
STATE=ROOT/'.bootstrap/observability/approach-c'
sys.path.insert(0,str(BACKEND))
from sources import secrets

URL='http://127.0.0.1:18103'


def request(path, body=None, operator=False):
    key='MOCKNET_C_API_OPERATOR_TOKEN' if operator else 'MOCKNET_C_API_READER_TOKEN'
    call=urllib.request.Request(URL+path,data=None if body is None else json.dumps(body).encode(),
        headers={'Authorization':'Bearer '+secrets()[key],'Content-Type':'application/json'})
    with urllib.request.urlopen(call,timeout=30) as response:
        return json.load(response)


def owned_pid():
    try:
        pid=int((STATE/'api.pid').read_text())
    except (FileNotFoundError,ValueError):
        return None
    command=subprocess.run(['ps','-p',str(pid),'-o','command='],capture_output=True,text=True).stdout
    return pid if 'gunicorn' in command and str(STATE/'api.pid') in command else None


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=['start','stop','status','sources','tools','runs','run'])
    parser.add_argument('tool',nargs='?',choices=['application-health','queue-diagnostics','operation-evidence'])
    parser.add_argument('--operation-id')
    args=parser.parse_args()
    if args.command=='start':
        if not owned_pid():
            with (STATE/'api.log').open('ab') as output:
                process=subprocess.Popen([str(STATE/'venv/bin/gunicorn'),'--chdir',str(BACKEND),
                    '--bind','127.0.0.1:18103','--workers','2','--threads','2','--timeout','30',
                    '--pid',str(STATE/'api.pid'),'--access-logfile','/dev/null',
                    'app:create_app()'],stdin=subprocess.DEVNULL,stdout=output,stderr=output,start_new_session=True)
            for _ in range(100):
                if process.poll() is not None:
                    raise RuntimeError('C API failed to start; inspect api.log')
                if owned_pid():
                    try:
                        with urllib.request.urlopen(URL+'/health',timeout=1) as response:
                            if json.load(response).get('service')=='approach-c-support-api':
                                break
                    except OSError:
                        pass
                time.sleep(.1)
            else:
                raise RuntimeError('C API did not become ready; inspect api.log')
    elif args.command=='stop':
        pid=owned_pid()
        if pid:
            os.kill(pid,signal.SIGTERM)
            for _ in range(100):
                if not owned_pid():
                    break
                time.sleep(.1)
            else:
                raise RuntimeError('C API did not stop')
    elif args.command in ('sources','tools','runs'):
        path={'sources':'sources','tools':'tools','runs':'tools/runs'}[args.command]
        print(json.dumps(request('/api/v1/'+path),indent=2))
        return
    elif args.command=='run':
        if not args.tool:
            parser.error('run requires a tool')
        body={'operation_id':args.operation_id} if args.operation_id else {}
        print(json.dumps(request('/api/v1/tools/'+args.tool+'/runs',body,True),indent=2))
        return
    print(json.dumps({'pid':owned_pid(),'url':URL,'service':'approach-c-support-api'}))


if __name__=='__main__':
    main()

"""Bounded source adapters. Database permissions enforce the query boundary."""
import datetime as dt
import json
import os
import pathlib
import urllib.request

import psycopg
from psycopg.rows import dict_row

ROOT = pathlib.Path(__file__).resolve().parents[3]
STATE = ROOT / '.bootstrap/observability/approach-c'


def now():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def secrets():
    path = pathlib.Path(os.environ.get('MOCKNET_C_SECRETS', ROOT / '.bootstrap/observability/grafana.env'))
    return dict(line.split('=', 1) for line in path.read_text().splitlines() if '=' in line)


class Sources:
    def __init__(self, config=None, credentials=None, state=STATE):
        self.config = config or json.loads(pathlib.Path(__file__).with_name('settings.json').read_text())
        if self.config['mode'] != 'local-emulation':
            raise ValueError('Only the configured local source emulation is implemented')
        for name,minimum,maximum in [('source_poll_seconds',1,60),('ods_refresh_seconds',1,3600),
                                      ('source_stale_seconds',5,3600),('max_rows',1,100)]:
            if type(self.config.get(name)) is not int or not minimum<=self.config[name]<=maximum:
                raise ValueError('Invalid backend setting: '+name)
        self.credentials = credentials if credentials is not None else secrets()
        self.state = state

    def connect(self, role, reporting=True):
        settings = self.config['database']
        return psycopg.connect(host=settings['host'], port=settings['port'],
            dbname=self.config['reporting_database'] if reporting else settings['name'], user=role,
            password=self.credentials['MOCKNET_C_BACKEND_DB_PASSWORD'], connect_timeout=3,
            row_factory=dict_row)

    def query(self, query, params=(), source=False):
        with self.connect('c_source_reader' if source else 'c_backend_reader', not source) as db:
            return db.execute(query, params).fetchmany(self.config['max_rows'])

    def database(self):
        # One statement gives all aggregate rows the same database snapshot time.
        rows = self.query('SELECT *,statement_timestamp() AS sampled_at FROM c_support.database_summary ORDER BY category,item', source=True)
        stamp = rows[0]['sampled_at'].isoformat() if rows else now()
        return stamp, [{k: r[k] for k in ('category','item','value')} for r in rows]

    def ods(self):
        path = self.state / 'ods/snapshot.json'
        with path.open('rb') as file:
            raw = file.read(65537)
        if len(raw) > 65536:
            raise ValueError('ODS export is oversized')
        data = json.loads(raw)
        if not isinstance(data,dict) or data.get('schema_version') != 1 or data.get('provenance') != 'local-emulation':
            raise ValueError('Unsupported ODS export')
        stamp = dt.datetime.fromisoformat(data['sampled_at'])
        if stamp.tzinfo is None or stamp > dt.datetime.now(dt.timezone.utc) + dt.timedelta(seconds=5):
            raise ValueError('Invalid ODS clock')
        rows = data['rows']
        if not isinstance(rows, list) or len(rows) > self.config['max_rows']:
            raise ValueError('Invalid ODS row count')
        for row in rows:
            if set(row) != {'category','item','value'} or any(not isinstance(row[k],str) or len(row[k])>128 for k in ('category','item')) or type(row['value']) is not int or row['value'] < 0:
                raise ValueError('Invalid ODS row')
        return stamp.isoformat(), rows

    def evidence(self):
        rows = self.query('SELECT * FROM c_evidence_health')
        if not rows:
            raise FileNotFoundError('No collector observation')
        health = rows[0]
        return health['collected_at'].isoformat(), [
            {'category':'collection','item':key,'value':health[key]}
            for key in ('pending_journal','quarantined_lines','incomplete_calls','internal_log_sequence_gaps')]

    def configuration(self):
        # Only these effective backend settings may be exposed, never raw files or env vars.
        return now(), [{'category':'backend','item':k,'value':self.config[k]} for k in
            ('mode','source_poll_seconds','ods_refresh_seconds','source_stale_seconds','max_rows')]

    def snapshots(self):
        results = []
        for source_id, label, read in [
            ('database','Database / local',self.database),
            ('ods','ODS / local export',self.ods),
            ('application','Application evidence',self.evidence),
            ('configuration','Backend settings',self.configuration),
        ]:
            result = dict(source_id=source_id,label=label,provenance='local-emulation',
                          attempted_at=now(),sampled_at=None,status='unavailable',rows=[],error_code=None)
            try:
                stamp, rows = read()
                age = (dt.datetime.now(dt.timezone.utc)-dt.datetime.fromisoformat(stamp)).total_seconds()
                result.update(sampled_at=stamp,rows=rows,status='stale' if age>self.config['source_stale_seconds'] else 'ok')
            except (OSError, ValueError, KeyError, TypeError, psycopg.Error) as error:
                # A broken adapter must not hide the other sources or disclose connection details.
                result['error_code'] = type(error).__name__
            results.append(result)
        for name in ('COR','LG2','UDG'):
            results.append(dict(source_id=name.lower(),label=name,provenance='corporate / not connected',
                attempted_at=now(),sampled_at=None,status='not configured',rows=[],error_code=None))
        return results

    def health(self):
        # Fixed administrator-controlled target; request inputs never supply URLs or commands.
        request = urllib.request.Request(self.config['application_health_url'])
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *_):
                return None
        with urllib.request.build_opener(NoRedirect).open(request, timeout=3) as response:
            raw = response.read(16385)
        if len(raw)>16384:
            raise ValueError('Oversized health response')
        data=json.loads(raw)
        if not isinstance(data,dict):
            raise ValueError('Invalid application health response')
        status = data.get('status')
        if status not in ('UP','DOWN','OUT_OF_SERVICE','UNKNOWN'):
            raise ValueError('Invalid application health response')
        return [{'application_status':status,'observed_at':now()}]

    def execute_tool(self, tool, operation_id):
        if tool == 'application-health':
            return self.health()
        if tool == 'queue-diagnostics':
            return self.query("SELECT stage,state,count(*) AS messages,max(extract(epoch FROM now()-available_at)) FILTER(WHERE state='ready') AS oldest_ready_seconds,(SELECT collected_at FROM c_evidence_health) AS evidence_collected_at FROM c_queue GROUP BY stage,state ORDER BY stage,state")
        return self.query('SELECT call_id,parent_call_id,component,stage,worker,evidence_state,outcome,error_type,started_at,finished_at,duration_ms,(SELECT collected_at FROM c_evidence_health) AS evidence_collected_at FROM c_call WHERE operation_id=%s ORDER BY started_at LIMIT 100', (operation_id,))

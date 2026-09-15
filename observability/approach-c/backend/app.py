"""Approach C source and read-only diagnostic REST service, served by Gunicorn."""
import hmac
import re
import uuid
import datetime as dt
from decimal import Decimal
from functools import wraps

import psycopg
from flask import Flask, g, jsonify, request
from flask.json.provider import DefaultJSONProvider
from psycopg.types.json import Jsonb

from sources import Sources, now

TOOLS = {
    'application-health': 'Read the configured application health endpoint',
    'queue-diagnostics': 'Read reconstructed queue state and oldest ready work',
    'operation-evidence': 'Inspect component evidence for one operation',
}


class EvidenceJSONProvider(DefaultJSONProvider):
    @staticmethod
    def default(value):
        if isinstance(value,(dt.datetime,dt.date)):
            return value.isoformat()
        if isinstance(value,Decimal):
            return float(value)
        return DefaultJSONProvider.default(value)


def create_app(sources=None):
    app = Flask(__name__)
    app.json = EvidenceJSONProvider(app)
    app.config['MAX_CONTENT_LENGTH'] = 4096
    backend = sources or Sources()
    reader = backend.credentials['MOCKNET_C_API_READER_TOKEN']
    operator = backend.credentials['MOCKNET_C_API_OPERATOR_TOKEN']
    if min(len(reader),len(operator)) < 32 or hmac.compare_digest(reader,operator):
        raise ValueError('Distinct reader and operator credentials are required')

    def require_role(required='reader'):
        def decorate(fn):
            @wraps(fn)
            def authenticated(*args, **kwargs):
                auth = request.headers.get('Authorization','')
                token = auth[7:] if auth.startswith('Bearer ') else ''
                if hmac.compare_digest(token.encode(), operator.encode()):
                    g.principal = 'local-operator'
                elif hmac.compare_digest(token.encode(), reader.encode()):
                    g.principal = 'local-reader'
                else:
                    return jsonify(error='unauthorized'),401,{'WWW-Authenticate':'Bearer'}
                if required=='operator' and g.principal!='local-operator':
                    return jsonify(error='operator role required'),403
                return fn(*args,**kwargs)
            return authenticated
        return decorate

    @app.after_request
    def response_headers(response):
        response.headers['Cache-Control'] = 'no-store'
        response.headers['X-Content-Type-Options'] = 'nosniff'
        return response

    @app.errorhandler(psycopg.Error)
    def database_error(_):
        return jsonify(error='reporting store unavailable'),503

    @app.get('/health')
    def health():
        return jsonify(status='ok',service='approach-c-support-api')

    @app.get('/api/v1/sources')
    @require_role()
    def sources_snapshot():
        return jsonify(sources=backend.snapshots(),mode=backend.config['mode'])

    @app.get('/api/v1/operations/<operation_id>')
    @require_role()
    def operation(operation_id):
        if not valid_operation(operation_id):
            return jsonify(error='invalid operation ID'),400
        rows=backend.query('SELECT * FROM c_operations WHERE operation_id=%s',(operation_id,))
        if not rows:
            return jsonify(error='operation not found'),404
        return jsonify(operation=rows[0],calls=backend.execute_tool('operation-evidence',operation_id),
                       limit=backend.config['max_rows'])

    @app.get('/api/v1/tools')
    @require_role()
    def tool_catalogue():
        return jsonify(tools=[{'id':k,'description':v,'read_only':True,'required_role':'operator'} for k,v in TOOLS.items()])

    @app.get('/api/v1/tools/runs')
    @require_role()
    def tool_runs():
        return jsonify(runs=backend.query('SELECT * FROM tool_runs ORDER BY requested_at DESC LIMIT 50'))

    @app.post('/api/v1/tools/<tool>/runs')
    @require_role('operator')
    def execute(tool):
        if tool not in TOOLS:
            return jsonify(error='unknown diagnostic'),404
        body=request.get_json(silent=True)
        if not isinstance(body,dict) or set(body)-{'operation_id'}:
            return jsonify(error='expected an object with only an optional operation_id'),400
        operation_id=body.get('operation_id')
        if tool=='operation-evidence' and not valid_operation(operation_id):
            return jsonify(error='operation_id required'),400
        if tool!='operation-evidence' and operation_id is not None:
            return jsonify(error='this diagnostic takes no arguments'),400
        run_id=str(uuid.uuid4())
        # Persist the request before execution. If the store is unavailable, do not run a tool.
        with backend.connect('c_backend_writer') as db:
            db.execute('INSERT INTO tool_runs(run_id,tool,requested_by,operation_id,status) VALUES(%s,%s,%s,%s,%s)',
                       (run_id,tool,g.principal,operation_id,'running'))
        status,error,result='completed',None,None
        try:
            result=backend.execute_tool(tool,operation_id)
        except (OSError,ValueError,psycopg.Error) as failure:
            status,error='failed',type(failure).__name__
        # Keep subsecond timestamps in both the API response and the stored result.
        result=app.json.loads(app.json.dumps(result))
        with backend.connect('c_backend_writer') as db:
            db.execute('UPDATE tool_runs SET finished_at=clock_timestamp(),status=%s,result=%s,error_code=%s WHERE run_id=%s',
                       (status,Jsonb(result),error,run_id))
        return jsonify(run_id=run_id,tool=tool,status=status,result=result,error_code=error,finished_at=now()),200 if status=='completed' else 502

    return app


def valid_operation(value):
    return isinstance(value,str) and re.fullmatch(r'[A-Za-z0-9_.:-]{1,128}',value) is not None

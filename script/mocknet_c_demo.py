"""Stable viewing window for a completed, bounded C demo workload."""
import datetime as dt
import json
import urllib.parse


def latest_seed(state):
    for path in sorted((state / 'seeds').glob('*.json'), reverse=True):
        manifest = json.loads(path.read_text())
        if manifest.get('state') == 'verified':
            return path, manifest
    return None


def seed_window(manifest):
    start = dt.datetime.fromisoformat(manifest['startedAt'])
    end = dt.datetime.fromisoformat(manifest['finishedAt'])
    return {'from': str(int(start.timestamp() * 1000) - 120000),
            'to': str(int(end.timestamp() * 1000) + 120000)}


def pin_links(manifest):
    window = seed_window(manifest)
    manifest['timeRange'] = window
    manifest['dashboardUrl'] = 'http://localhost:3302/d/mocknet-c-business?' + urllib.parse.urlencode(window)
    for row in manifest['scenarios']:
        if row.get('operationId'):
            row['processUrl'] = 'http://localhost:3302/d/mocknet-c-process?' + urllib.parse.urlencode({
                **window, 'var-operation': row['operationId'],
                'var-overview_from': window['from'], 'var-overview_to': window['to']})


def pin_dashboard(dashboard, manifest):
    window = seed_window(manifest)
    dashboard['time'] = {key: dt.datetime.fromtimestamp(int(value) / 1000, dt.timezone.utc).isoformat()
                         for key, value in window.items()}
    dashboard['refresh'] = ''
    for variable in dashboard['templating']['list']:
        if variable['name'] == 'operation':
            operations = [row for row in manifest['scenarios'] if row.get('operationId')]
            if operations:
                selected = next((row for row in operations if row.get('label') == 'Healthy pair, first leg'), operations[0])
                variable['current'] = {'text': selected.get('tradeId', selected['operationId']), 'value': selected['operationId']}
                ids = ','.join("'" + row['operationId'].replace("'", "''") + "'" for row in operations)
                variable['query'] = ("SELECT coalesce(business_id,operation_id) AS __text,operation_id AS __value "
                                     "FROM c_operations WHERE operation_id IN (" + ids + ") "
                                     "AND (${search:sqlstring}='' OR operation_id=${search:sqlstring} "
                                     "OR business_id=${search:sqlstring}) ORDER BY admitted_at")
        for key in ('from', 'to'):
            if variable['name'] == 'overview_' + key:
                variable['query'] = window[key]
                variable['current'] = {'text': window[key], 'value': window[key]}

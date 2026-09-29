#!/usr/bin/env python3
"""Validate all C reporting/Tempo/Loki/Prometheus panels through Grafana."""
import hashlib
import json
import pathlib
import re
import time
import urllib.request
from mocknet_c_demo import latest_seed, seed_window

ROOT=pathlib.Path(__file__).resolve().parents[1]
STATE=ROOT/'.bootstrap/observability/approach-c'


def panels_in(dashboard):
    """Yield query panels inside regular and collapsed Grafana rows."""
    def walk(items):
        for item in items:
            if item.get('type') == 'row':
                yield from walk(item.get('panels', []))
            elif item.get('targets'):
                yield item
    yield from walk(dashboard.get('panels', []))


def check_layout_and_navigation(dashboards):
    expected = {'mocknet-c-business', 'mocknet-c-process', 'mocknet-c-overview',
                'mocknet-c-queues', 'mocknet-c-investigation', 'mocknet-c-sources', 'mocknet-c-runtime'}
    by_uid = {item['uid']: item for item in dashboards}
    assert expected <= by_uid.keys(), expected - by_uid.keys()
    def urls(value):
        if isinstance(value, dict):
            for key, item in value.items():
                if key == 'url' and isinstance(item, str):
                    yield item
                yield from urls(item)
        elif isinstance(value, list):
            for item in value:
                yield from urls(item)

    for dashboard in dashboards:
        assert all(':3300' not in url and 'mocknet-b-' not in url and 'mocknet-traces' not in url
                   for url in urls(dashboard)), dashboard['uid']
        assert len(dashboard['links']) <= 3, (dashboard['uid'], 'crowded navigation')
        assert not any('compare' in link['title'].lower() or ':3300' in link.get('url', '')
                       for link in dashboard['links']), dashboard['uid']
        for item in panels_in(dashboard):
            assert item['datasource'], (dashboard['uid'], item['title'])
            assert item['targets'], (dashboard['uid'], item['title'])
    business = by_uid['mocknet-c-business']
    detail = by_uid['mocknet-c-process']
    assert len(list(panels_in(business))) <= 9
    assert [p['title'] for p in business['panels'][:4]] == [
        'Trades received', 'Instructions ready', 'Completed on time', 'Needs attention']
    assert next(p for p in business['panels'] if p['title'] == 'Evidence and target')['gridPos']['y'] == 4
    assert len([p for p in detail['panels'] if p['type'] != 'row']) <= 6
    assert [p['title'] for p in detail['panels'][:3]] == ['Selected trade', 'Process progress', 'Process timeline']
    assert next(p for p in detail['panels'] if p['title'] == 'Stage timing')['gridPos']['y'] == 16
    assert next(p for p in detail['panels'] if p['title'] == 'SLA milestones')['gridPos']['y'] == 23
    progress = next(p for p in detail['panels'] if p['title'] == 'Process progress')
    assert progress['type'] == 'stat' and 'c_process_stages(' in progress['targets'][0]['rawSql']
    assert {m['options']['pattern'].split('\\')[0].lstrip('^') for m in progress['fieldConfig']['defaults']['mappings']} >= {'done', 'late', 'open', 'fail', 'gap', 'todo'}
    timeline = next(p for p in detail['panels'] if p['title'] == 'Process timeline')
    assert timeline['type'] == 'barchart' and timeline['options']['orientation'] == 'horizontal'
    assert timeline['options']['stacking'] == 'normal' and timeline['targets'][0]['format'] == 'table'
    assert 'c_process_stages(' in timeline['targets'][0]['rawSql'] and '$__timeFilter' not in timeline['targets'][0]['rawSql']
    assert [s['value'] for s in timeline['fieldConfig']['defaults']['thresholds']['steps'][1:]] == [5, 30, 60]
    summary_sql = next(p for p in detail['panels'] if p['title'] == 'Selected trade')['targets'][0]['rawSql']
    assert 'c_process_stages(' not in summary_sql
    assert all(v['type'] == 'textbox' for v in detail['templating']['list'] if v['name'] in ('overview_from', 'overview_to'))
    assert any(p['type'] == 'row' and p['collapsed'] for p in detail['panels'])
    for item in (business, detail):
        positioned = [p for p in item['panels'] if p['type'] != 'row']
        for index, left in enumerate(positioned):
            a = left['gridPos']
            assert 0 <= a['x'] and a['x'] + a['w'] <= 24
            for right in positioned[index + 1:]:
                b = right['gridPos']
                overlap = a['x'] < b['x'] + b['w'] and b['x'] < a['x'] + a['w'] and a['y'] < b['y'] + b['h'] and b['y'] < a['y'] + a['h']
                assert not overlap, (item['uid'], left['title'], right['title'])
    overview_links = [value['url'] for panel in panels_in(business)
                      for override in panel['fieldConfig']['overrides']
                      for prop in override['properties'] if prop['id'] == 'links'
                      for value in prop['value']]
    assert any('/d/mocknet-c-process?' in url and 'var-operation=' in url and 'window_from:raw' in url and 'window_to:raw' in url for url in overview_links)
    trade_query = next(panel['targets'][0]['rawSql'] for panel in business['panels'] if panel['title'] == 'Trades by priority')
    assert '::bigint::text AS window_from' in trade_query and '::bigint::text AS window_to' in trade_query
    service_query = by_uid['mocknet-c-overview']['panels'][0]['targets'][0]['rawSql']
    assert '::bigint::text AS window_from' in service_query and '::bigint::text AS window_to' in service_query
    assert any('/d/mocknet-c-business?' in link['url'] and 'overview_from' in link['url'] for link in detail['links'])
    assert 'timeFilter' not in detail['templating']['list'][1]['query']


def main():
    import psycopg
    seed = latest_seed(STATE)
    window = seed_window(seed[1]) if seed else {'from': str(int((time.time()-1800)*1000)), 'to': str(int(time.time()*1000))}
    secret=dict(x.split('=',1) for x in (ROOT/'.bootstrap/observability/grafana.env').read_text().splitlines())
    with psycopg.connect(host='127.0.0.1',port=15452,dbname='telemetry_c',user='grafana_c',password=secret['MOCKNET_READER_PASSWORD']) as db:
        if seed:
            operations = [row['operationId'] for row in seed[1]['scenarios'] if row.get('operationId')]
            found=db.execute("SELECT operation_id FROM c_operation_trace WHERE export_state='exported' AND operation_id=ANY(%s) ORDER BY sent_at DESC LIMIT 1", (operations,)).fetchone()
        else:
            found=db.execute("SELECT t.operation_id FROM c_operation_trace t JOIN c_operations o USING(operation_id) WHERE t.export_state='exported' AND t.sent_at<now()-interval '30 seconds' AND o.admitted_at>now()-interval '20 minutes' ORDER BY t.sent_at DESC LIMIT 1").fetchone()
        assert found, 'Wait for an exported C operation before checking all panels'
        operation=found[0]
        assert not db.execute("SELECT has_database_privilege(current_user,'mocknet_c','CONNECT')").fetchone()[0]
        assert db.execute('SHOW default_transaction_read_only').fetchone()[0]=='on'
    trace=hashlib.md5(('c-reconstruction:'+operation).encode()).hexdigest()
    results=[]
    values={'stage':'.*','operation':operation,'search':'','trace_id':trace,'policy':'instructions',
            'service':'mocknet','environment':'local-demo','version':'.*','app_instance':'.*'}
    dashboards=[json.loads(file.read_text()) for file in sorted((ROOT/'observability/approach-c/grafana').glob('*.json'))]
    check_layout_and_navigation(dashboards)
    for dashboard in dashboards:
        for panel in panels_in(dashboard):
            queries=[]
            kind=panel['datasource']['type']
            for original in panel['targets']:
                target=dict(original,datasource=panel['datasource'],intervalMs=5000,maxDataPoints=300)
                for field in ('rawSql','expr','query'):
                    if field not in target:continue
                    q=target[field]
                    for name,value in values.items():
                        q=q.replace('${'+name+':sqlstring}',"'"+value.replace("'","''")+"'")
                        q=q.replace('${'+name+':doublequote}',json.dumps(value))
                        q=q.replace('${'+name+':regex}',value).replace('${'+name+'}',value).replace('$'+name,value)
                    q=q.replace('$__rate_interval','1m')
                    target[field]=q
                if kind=='grafana-postgresql-datasource':
                    assert panel['datasource']['uid']=='mocknet-c-reporting'
                    assert not re.search(r'\bsupport\.',target['rawSql'])
                elif kind=='tempo' and re.fullmatch('[a-f0-9]{32}',target.get('query','')):
                    target['queryType']='traceId'
                queries.append(target)
            body={**window, 'queries':queries}
            request=urllib.request.Request('http://localhost:3302/api/ds/query',data=json.dumps(body).encode(),headers={'Content-Type':'application/json'})
            started = time.monotonic()
            with urllib.request.urlopen(request,timeout=40) as response:result=json.load(response)
            elapsed_ms = round((time.monotonic() - started) * 1000, 1)
            errors=[v['error'] for v in result['results'].values() if v.get('error')]
            assert not errors,(panel['title'],errors)
            frames=[f for v in result['results'].values() for f in v.get('frames',[])]
            count=sum(len(f.get('data',{}).get('values',[[]])[0]) if f.get('data',{}).get('values') else 0 for f in frames)
            if panel['type']=='traces':
                assert count>0,(panel['title'],'empty waterfall')
                if kind=='grafana-postgresql-datasource':
                    frame=frames[0];names=[f['name'] for f in frame['schema']['fields']]
                    rows=[dict(zip(names,r)) for r in zip(*frame['data']['values'])]
                    ids={r['spanID'] for r in rows}
                    assert len(ids)==len(rows) and sum(r['parentSpanID'] is None for r in rows)==1
                    assert all(r['parentSpanID'] is None or r['parentSpanID'] in ids for r in rows)
            if kind=='loki':assert count>0,'correlated logs empty'
            if dashboard['uid'] in ('mocknet-c-business', 'mocknet-c-process'):
                assert count>0,(panel['title'],'seeded business panel empty')
            if panel['title']=='Advanced reconstructed trace search':assert count>0,'time-bounded trace search empty'
            results.append({'dashboard':dashboard['uid'],'panel':panel['title'],'datasource':kind,'frames':len(frames),'rows':count,'elapsedMs':elapsed_ms})
    output={'at':time.time(),'operation':operation,'traceId':trace,'seedRunId': seed[1]['runId'] if seed else None,
            'timeRange':window,'queries':results,'reportReaderCannotConnectToApplicationDatabase':True}
    (STATE/'dashboard-validation.json').write_text(json.dumps(output,indent=2)+'\n')
    print(json.dumps(output,indent=2))


if __name__=='__main__':main()

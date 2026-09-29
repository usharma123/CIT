"""Add Grafana exploration and metrics to C's existing reporting dashboards."""
import copy
import json
from urllib.parse import quote

PROM = {'type': 'prometheus', 'uid': 'mocknet-c-prometheus'}
TEMPO = {'type': 'tempo', 'uid': 'mocknet-c-tempo'}
LOKI = {'type': 'loki', 'uid': 'mocknet-c-loki'}


def extend(root, dashboards, panel):
    overview, queues, investigation, sources = dashboards
    shared = overview['links']
    for d in dashboards:
        d['description'] = 'Committed journals and structured logs feed reporting, Tempo, and Loki. Agent-free Micrometer metrics feed Prometheus. Business counts use committed records; span and log rates count observations.'
    # The main operation list exposes business state, separately from queue disposition.
    p = overview['panels'][0]
    p['targets'][0]['rawSql'] = p['targets'][0]['rawSql'].replace('SELECT business_id,disposition,', 'SELECT business_id,business_outcome,recovered,disposition,').replace('FROM c_operations WHERE', 'FROM c_business_operations WHERE')
    p['description'] += ' Business outcome distinguishes awaiting a counterparty from completed local processing. Recovery is a subset, not another operation.'
    panel(overview, 'Business outcomes', "SELECT business_outcome,count(*) AS operations,count(*) FILTER(WHERE recovered) AS recovered_subset FROM c_business_operations WHERE $__timeFilter(admitted_at) GROUP BY business_outcome ORDER BY business_outcome", 0, 42, 24, 7)
    panel(queues, 'Queue arrivals and terminal departures per minute', """WITH events AS (
        SELECT created_at AS at,stage,'arrived' AS flow FROM c_queue WHERE $__timeFilter(created_at)
        UNION ALL SELECT last_event_at,stage,CASE WHEN status='FAILED' THEN 'failed' WHEN outcome='rejected' THEN 'rejected' ELSE 'completed' END
        FROM c_queue WHERE status IN('DONE','FAILED') AND $__timeFilter(last_event_at))
        SELECT date_trunc('minute',at) AS time,stage||' '||flow AS metric,count(*)::double precision AS value
        FROM events WHERE stage ~ ${stage:sqlstring} AND stage<>'DEAD_LETTER' GROUP BY 1,2 ORDER BY 1,2""", 0, 39, 24, 8, 'timeseries',
        description='One arrival and one terminal departure per message. Retries are attempts, not extra departures. First/last minute buckets may be partial.')
    investigation['templating']['list'].append({'name': 'trace_id', 'label': 'Reconstructed trace ID', 'type': 'query',
        'datasource': {'type': 'grafana-postgresql-datasource', 'uid': 'mocknet-c-reporting'}, 'hide': 2,
        'query': "SELECT md5('c-reconstruction:'||${operation:sqlstring}) AS __text,md5('c-reconstruction:'||${operation:sqlstring}) AS __value", 'refresh': 1})
    for p in investigation['panels']:
        p['gridPos']['y'] += 4
    p = panel(investigation, 'Trace export status', "SELECT operation_id,trace_id,export_state,sent_at,error_code FROM c_operation_trace WHERE operation_id=${operation:sqlstring}", 0, 0, 24, 4,
        description='Tempo holds one immutable settled-operation snapshot after a quiet collection interval. Unfinished work stays available in the live SQL waterfall. Changed-after-snapshot evidence remains in reporting and is flagged here. Ambiguous delivery is not blindly retried.')
    p['fieldConfig']['overrides'].append({'matcher': {'id': 'byName', 'options': 'trace_id'}, 'properties': [{'id': 'links', 'value': [{
        'title': 'Open in Traces Drilldown', 'url': '/a/grafana-exploretraces-app/explore?var-ds=mocknet-c-tempo&traceId=${__value.raw}&var-primarySignal=nestedSetParent%3C0&var-metric=rate&var-groupBy=resource.service.name&actionView=breakdown&${__url_time_range}'}]}]})
    p = panel(investigation, 'Related operations and matched legs', "SELECT l.related_operation_id,o.business_id,o.business_outcome,l.matched_trade_id FROM c_operation_links l LEFT JOIN c_business_operations o ON o.operation_id=l.related_operation_id WHERE l.operation_id=${operation:sqlstring}", 0, 81, 24, 7,
        description='Recorded matched-leg identities. Open another leg to inspect its own trace. Shared netting is not counted again in this operation.')
    p['fieldConfig']['overrides'].append({'matcher': {'id': 'byName', 'options': 'related_operation_id'}, 'properties': [{'id': 'links', 'value': [{
        'title': 'Inspect related operation', 'url': '/d/mocknet-c-investigation?var-operation=${__value.raw}&var-search=${__value.raw}&${__url_time_range}'}]}]})
    p = panel(investigation, 'Correlated application logs and evidence', '', 0, 88, 24, 12, 'logs')
    p['datasource'] = LOKI
    p['targets'] = [{'refId': 'A', 'datasource': LOKI, 'expr': '{service_name="mocknet"} | operation_id=${operation:doublequote}', 'queryType': 'range', 'editorMode': 'code'}]
    p['options'] = {'showTime': True, 'showLabels': False, 'wrapLogMessage': True, 'sortOrder': 'Ascending', 'enableLogDetails': True}
    p['description'] = 'Exact operation filter on structured metadata. Start/end/journal records are observations of the same work, not additional business outcomes. Application diagnostics omit raw payloads and exception messages.'
    p = panel(investigation, 'Advanced reconstructed trace search', '', 0, 100, 24, 10)
    p['datasource'] = TEMPO
    p['targets'] = [{'refId': 'A', 'datasource': TEMPO, 'queryType': 'traceql', 'query': '{ span.operation_id = "${operation}" }', 'tableType': 'traces', 'limit': 20}]
    p['description'] = 'Search Tempo-backed reconstructed traces, then open a trace for full structure and trace-to-logs/metrics links.'
    p = panel(investigation, 'Indexed trace waterfall', '', 0, 110, 24, 18, 'traces')
    p['datasource'] = TEMPO
    p['targets'] = [{'refId': 'A', 'datasource': TEMPO, 'queryType': 'traceql', 'query': '${trace_id}'}]
    p['options'] = {}
    p['description'] = 'Tempo snapshot for settled operations. The SQL waterfall above remains the current view of incomplete or later evidence. All stages belong to the single mocknet service.'
    panel(sources, 'Trace and log export health', 'SELECT * FROM c_telemetry_health', 0, 44, 24, 8,
        description='Exporter age, pending records, uncertain deliveries and post-snapshot changes remain visible. Business history is independent of Tempo/Loki delivery. No alarm or notification actions.')

    template = json.loads((root / 'observability/grafana/dashboards/stages.json').read_text())
    runtime = copy.deepcopy(template)
    runtime.update(uid='mocknet-c-runtime', title='Runtime and queue metrics', links=shared,
                   description='Agent-free Micrometer metrics from C only. JVM, HTTP, worker and pool metrics plus committed attempt histograms. Queue gauges represent the shared database and are not summed across exporters.')
    runtime['annotations'] = {'list': []}
    runtime['templating']['list'] = [v for v in runtime['templating']['list'] if v['name'] != 'business_id']
    keep = []
    for p in runtime['panels']:
        if p.get('datasource', {}).get('type') != 'prometheus' or any(x in p['title'].lower() for x in ('collector', 'alert history')):
            continue
        p['datasource'] = PROM
        keep.append(p)
    runtime['panels'] = keep
    # Data source references appear in template variables and panel targets as well.
    runtime = json.loads(json.dumps(runtime).replace('mocknet-prometheus', 'mocknet-c-prometheus').replace('Approach A', 'Runtime'))
    selector = 'service=~"$service",environment=~"$environment",version=~"$version",app_instance=~"$app_instance"'
    extra = [
        ('Process CPU', f'process_cpu_usage{{{selector}}}', 'percentunit'),
        ('JVM non-heap used', f'sum(jvm_memory_used_bytes{{{selector},area="nonheap"}})', 'bytes'),
        ('JVM live threads', f'jvm_threads_live_threads{{{selector}}}', 'short'),
        ('Loaded classes', f'jvm_classes_loaded_classes{{{selector}}}', 'short'),
        ('HTTP request rate', f'sum by(uri)(rate(http_server_requests_seconds_count{{{selector},uri!="/actuator/prometheus"}}[$__rate_interval]))', 'reqps'),
        ('HTTP admission latency p95', f'histogram_quantile(0.95,sum by(le)(rate(http_server_requests_seconds_bucket{{{selector},uri="/api/trades"}}[$__rate_interval])))', 's'),
        ('HTTP server error rate', f'sum(rate(http_server_requests_seconds_count{{{selector},status=~"5.."}}[$__rate_interval])) or (0 * sum(rate(http_server_requests_seconds_count{{{selector}}}[$__rate_interval])))', 'reqps'),
        ('GC allocation rate', f'sum(rate(jvm_gc_memory_allocated_bytes_total{{{selector}}}[$__rate_interval]))', 'Bps'),
        ('Open file descriptors', f'process_files_open_files{{{selector}}}', 'short'),
    ]
    for title, query, unit in extra:
        p = copy.deepcopy(keep[0])
        p.update(title=title, datasource=PROM, description='Measured by C Micrometer without a Java agent. HTTP admission latency is separate from reconstructed operation elapsed time.')
        p['targets'] = [{'refId': 'A', 'datasource': PROM, 'expr': query, 'legendFormat': '{{uri}}' if title == 'HTTP request rate' else title, 'range': True}]
        p['fieldConfig']['defaults']['unit'] = unit
        runtime['panels'].append(p)
    for n, p in enumerate(runtime['panels']):
        p['id'] = n+1
        p['gridPos'] = {'x': n%2*12, 'y': n//2*8, 'w': 12, 'h': 8}
    for d in (*dashboards, runtime):
        for n, p in enumerate(d['panels']):
            p['id'] = n+1
    return runtime

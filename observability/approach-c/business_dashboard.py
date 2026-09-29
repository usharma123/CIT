"""Business overview and process detail from committed reporting evidence."""

DS = {'type': 'grafana-postgresql-datasource', 'uid': 'mocknet-c-reporting'}
# Demo targets seeded into c_sla_policy (business.sql); drawn as timeline reference lines.
SLA_TARGETS_SECONDS = (5, 30, 60)
SLA_COLORS = {'Met': 'green', 'In progress': 'blue', 'At risk': 'yellow', 'Breached': 'orange', 'Failed': 'red',
              'Stale': '#8e8e8e', 'Unknown': '#6e7079', 'Excluded': '#4a4d55'}


def _style_table(p, labels, widths=None, status_field=None):
    widths = widths or {}
    p['fieldConfig']['defaults']['noValue'] = 'Not recorded'
    for field, label in labels.items():
        properties = [{'id': 'displayName', 'value': label}]
        if field in widths:
            properties.append({'id': 'custom.width', 'value': widths[field]})
        p['fieldConfig']['overrides'].append({'matcher': {'id': 'byName', 'options': field}, 'properties': properties})
    if status_field:
        colors = {'Met': 'green', 'Breached': 'orange', 'At risk': 'yellow', 'Failed': 'red',
                  'In progress': 'blue', 'Unknown': 'gray', 'Stale': 'gray', 'Excluded': 'gray',
                  'Instructions ready': 'green', 'Waiting for counterparty': 'yellow',
                  'Rejected': 'red', 'Netted; output evidence missing': 'gray',
                  'Completed': 'green', 'Observed open': 'yellow', 'Missing evidence': 'gray',
                  'Not reached': 'gray', 'Not required': 'gray', 'Not applicable': 'gray'}
        p['fieldConfig']['overrides'].append({'matcher': {'id': 'byName', 'options': status_field}, 'properties': [
            {'id': 'mappings', 'value': [{'type': 'value', 'options': {name: {'text': name, 'color': color} for name, color in colors.items()}}]},
            {'id': 'custom.cellOptions', 'value': {'type': 'color-text'}}]})


def _stat(panel, d, title, query, x, y, unit='short', detail='', attention=False):
    p = panel(d, title, query, x, y, 6, 4, 'stat', unit, detail)
    p['options'] = {'reduceOptions': {'calcs': ['lastNotNull'], 'fields': '', 'values': False},
                    'colorMode': 'value', 'graphMode': 'none', 'textMode': 'value', 'justifyMode': 'center'}
    defaults = {'unit': unit, 'decimals': 1 if unit == 'percent' else 0,
                'noValue': 'N/A', 'color': {'mode': 'fixed', 'fixedColor': 'blue'}}
    if attention:
        defaults['color'] = {'mode': 'thresholds'}
        defaults['thresholds'] = {'mode': 'absolute', 'steps': [{'color': 'blue', 'value': None}, {'color': 'red', 'value': 1}]}
    p['fieldConfig']['defaults'] = defaults
    return p


def _process_link(p, field='business_id'):
    url = ('/d/mocknet-c-process?from=${__data.fields.window_from:raw}&to=${__data.fields.window_to:raw}'
           '&var-operation=${__data.fields.operation_id}&var-search=${__data.fields.operation_id}'
           '&var-policy=${policy}&var-overview_from=${__from}&var-overview_to=${__to}')
    p['fieldConfig']['overrides'].append({'matcher': {'id': 'byName', 'options': field}, 'properties': [
        {'id': 'links', 'value': [{'title': 'View process', 'url': url}]}]})
    for field_name in ('window_from', 'window_to', 'operation_id'):
        if field_name != field:
            p['fieldConfig']['overrides'].append({'matcher': {'id': 'byName', 'options': field_name}, 'properties': [
                {'id': 'custom.hidden', 'value': True}, {'id': 'unit', 'value': 'none'}, {'id': 'decimals', 'value': 0}]})


def build(dashboard, panel, drill, datasource):
    overview = dashboard('mocknet-c-business', 'Business process overview')
    overview['tags'] = ['mocknet', 'business', 'observability']
    overview['time'] = {'from': 'now-1h', 'to': 'now'}
    overview['refresh'] = '30s'
    overview['links'] = [
        {'type': 'link', 'title': 'Service health', 'url': '/d/mocknet-c-overview', 'keepTime': True},
        {'type': 'link', 'title': 'Queue diagnostics', 'url': '/d/mocknet-c-queues', 'keepTime': True},
    ]
    overview['description'] = ('One row per received trade. Dashboard time selects the admission cohort. '
                               'Demo SLA targets measure receipt to validation (5s), matching (30s), and instruction readiness (60s). '
                               'External settlement is outside this view. Event history retention defaults to 90 days.')
    overview['templating']['list'] = [
        {'name': 'policy', 'label': 'SLA milestone', 'type': 'custom',
         'query': 'Validation : validation,Matching : matching,Instruction readiness : instructions',
         'current': {'text': 'Instruction readiness', 'value': 'instructions'}},
        {'name': 'search', 'label': 'Find trade / operation', 'type': 'textbox', 'query': '',
         'current': {'text': '', 'value': ''},
         'description': 'Filters the cohort and process list by exact trade or operation ID.'},
    ]
    cohort = "WHERE $__timeFilter(admitted_at) AND (${search:sqlstring}='' OR business_id=${search:sqlstring} OR operation_id=${search:sqlstring})"
    policy = cohort + ' AND policy_id=${policy:sqlstring}'
    _stat(panel, overview, 'Trades received', 'SELECT count(*) FROM c_process_summary ' + cohort, 0, 0,
          detail='Trades admitted in the selected time range. Both legs of a matched pair count separately.')
    _stat(panel, overview, 'Instructions ready', "SELECT count(*) FROM c_process_summary " + cohort + " AND business_state='Instructions ready'", 6, 0,
          detail='Local netting and required instruction generation recorded. This does not mean external settlement.')
    _stat(panel, overview, 'Completed on time',
          "SELECT 100.0*count(*) FILTER (WHERE sla_status='Met')/nullif(count(*) FILTER (WHERE finished_at IS NOT NULL AND sla_status IN('Met','Breached')),0) FROM c_process_sla " + policy,
          12, 0, 'percent', 'Selected SLA milestone. Denominator includes only completed, known eligible processes. N/A means none completed.')
    _stat(panel, overview, 'Needs attention',
          "SELECT count(*) FROM c_process_sla " + policy + " AND sla_status IN('Breached','At risk','Failed')", 18, 0,
          detail='Selected milestone: breached, near target, or failed. Unknown and stale evidence are shown separately below.', attention=True)
    # Workflow snapshot: current state counts in process order, left to right.
    stages = [('Validating', "'Received / processing'", 'blue'), ('Waiting for counterparty', "'Waiting for counterparty'", 'blue'),
              ('Matched · netting', "'Matched'", 'blue'), ('Output evidence missing', "'Netted; output evidence missing'", 'purple'),
              ('Instructions ready', "'Instructions ready'", 'green'), ('Rejected', "'Rejected'", 'red'), ('Failed', "'Failed'", 'red')]
    p = panel(overview, 'Where trades are now',
              'SELECT ' + ','.join(f'count(*) FILTER(WHERE business_state={state}) AS "{name}"' for name, state, _ in stages)
              + ' FROM c_process_summary ' + cohort,
              0, 8, 24, 4, 'stat',
              description='Current business state of trades admitted in the selected range, in process order. '
                          'Blue stages are still in flight; purple means netting finished but instruction evidence is incomplete.')
    p['options'] = {'reduceOptions': {'calcs': ['lastNotNull'], 'fields': '', 'values': False},
                    'orientation': 'vertical', 'textMode': 'value_and_name', 'colorMode': 'background_solid',
                    'graphMode': 'none', 'justifyMode': 'center', 'wideLayout': True,
                    'text': {'titleSize': 13, 'valueSize': 26}}
    p['fieldConfig']['defaults'] = {'unit': 'short', 'decimals': 0, 'noValue': '0', 'color': {'mode': 'thresholds'},
                                    'thresholds': {'mode': 'absolute', 'steps': [{'color': '#3a3d45', 'value': None}]}}
    for name, _, color in stages:
        p['fieldConfig']['overrides'].append({'matcher': {'id': 'byName', 'options': name}, 'properties': [
            {'id': 'thresholds', 'value': {'mode': 'absolute', 'steps': [{'color': '#3a3d45', 'value': None}, {'color': color, 'value': 1}]}}]})
    # How long trades take for the selected milestone, bucketed relative to its target.
    statuses = ['Met', 'In progress', 'At risk', 'Breached', 'Stale']
    p = panel(overview, 'Time to milestone vs target',
              "WITH t AS (SELECT target_seconds AS x FROM c_sla_policy WHERE policy_id=${policy:sqlstring}),"
              "buckets(n,lo,hi) AS (VALUES (1,0.0,0.5),(2,0.5,1.0),(3,1.0,2.0),(4,2.0,NULL)),"
              "labelled AS (SELECT n,lo*x AS lo_s,hi*x AS hi_s,CASE n WHEN 1 THEN 'Under '||c_format_duration(hi*x) "
              "WHEN 4 THEN 'Over '||c_format_duration(lo*x) ELSE c_format_duration(lo*x)||' – '||c_format_duration(hi*x) END"
              "||CASE n WHEN 1 THEN '  (fast)' WHEN 2 THEN '  (within target)' WHEN 3 THEN '  (late)' ELSE '  (very late)' END AS bucket "
              "FROM buckets CROSS JOIN t),"
              "trades AS (SELECT elapsed_seconds,sla_status FROM c_process_sla " + policy
              + " AND sla_status IN(" + ','.join(f"'{st}'" for st in statuses) + ")) "
              "SELECT l.bucket||'  ·  '||count(s.*) AS bucket," + ','.join(f"count(s.*) FILTER(WHERE s.sla_status='{st}') AS \"{st}\"" for st in statuses)
              + " FROM labelled l LEFT JOIN trades s ON s.elapsed_seconds>=l.lo_s AND (l.hi_s IS NULL OR s.elapsed_seconds<l.hi_s) "
              "GROUP BY l.n,l.bucket ORDER BY l.n",
              0, 12, 12, 8, 'barchart', 'short',
              description='Elapsed time from receipt to the selected SLA milestone, grouped relative to its demo target, with the trade count. '
                          'Open trades use time waited so far, so an open trade already past target shows as breached. '
                          'Failed, rejected (excluded) and unknown trades have no meaningful duration; see Needs attention and the trade list.')
    p['options'] = {'orientation': 'horizontal', 'xField': 'bucket', 'stacking': 'normal', 'showValue': 'never',
                    'barWidth': 0.7, 'groupWidth': 0.7, 'barRadius': 0.1, 'xTickLabelRotation': 0, 'xTickLabelMaxLength': 0,
                    'legend': {'showLegend': True, 'displayMode': 'list', 'placement': 'bottom'},
                    'tooltip': {'mode': 'multi', 'sort': 'none', 'hideZeros': True}}
    p['fieldConfig']['defaults'] = {'unit': 'short', 'decimals': 0, 'min': 0, 'color': {'mode': 'fixed', 'fixedColor': 'gray'},
                                    'custom': {'fillOpacity': 85, 'lineWidth': 0, 'axisLabel': 'Trades', 'axisSoftMin': 0}}
    for name, color in SLA_COLORS.items():
        p['fieldConfig']['overrides'].append({'matcher': {'id': 'byName', 'options': name},
                                              'properties': [{'id': 'color', 'value': {'mode': 'fixed', 'fixedColor': color}}]})
    # Volume and outcome over time; bucket width follows the selected range so bursts stay readable.
    p = panel(overview, 'Trades received by SLA outcome',
              "SELECT $__timeGroupAlias(admitted_at,$__interval,0),sla_status AS metric,count(*)::double precision AS value "
              "FROM c_process_sla " + policy + ' GROUP BY 1,2 ORDER BY 1,2',
              12, 12, 12, 8, 'timeseries', 'short',
              'Trades by admission time, stacked by the selected milestone\'s SLA status. Bar width adapts to the time range. '
              'Unknown, stale and excluded stay visible instead of counting as compliant.')
    p['maxDataPoints'] = 40
    p['interval'] = '5s'
    p['fieldConfig']['defaults']['custom'].update({'drawStyle': 'bars', 'fillOpacity': 85, 'lineWidth': 0, 'barAlignment': 0,
                                                   'stacking': {'mode': 'normal', 'group': 'A'}, 'axisSoftMin': 0})
    p['fieldConfig']['defaults']['decimals'] = 0
    p['options']['tooltip'] = {'mode': 'multi', 'sort': 'desc', 'hideZeros': True}
    for name, color in SLA_COLORS.items():
        p['fieldConfig']['overrides'].append({'matcher': {'id': 'byName', 'options': name},
                                              'properties': [{'id': 'color', 'value': {'mode': 'fixed', 'fixedColor': color}}]})
    p = panel(overview, 'Trades by priority',
              "SELECT business_id,business_state,sla_status,round(elapsed_seconds::numeric,1) AS elapsed_seconds,operation_id,"
              "(extract(epoch FROM coalesce(received_at,admitted_at))*1000-1000)::bigint::text AS window_from,"
              "(extract(epoch FROM coalesce(instructions_at,rejected_at,collected_at,admitted_at))*1000+1000)::bigint::text AS window_to "
              'FROM c_process_sla ' + policy + " ORDER BY CASE sla_status WHEN 'Breached' THEN 0 WHEN 'Failed' THEN 1 WHEN 'At risk' THEN 2 WHEN 'Stale' THEN 3 WHEN 'Unknown' THEN 4 ELSE 5 END, admitted_at DESC LIMIT 100",
              0, 20, 24, 10, 'table', description='Most urgent trades first. Click a trade to open its complete process history. Empty means no admitted trades in this window or search.')
    _process_link(p)
    p['options']['wrapText'] = True
    p['options']['cellHeight'] = 'md'
    _style_table(p, {'business_id': 'Trade', 'business_state': 'Business state', 'sla_status': 'SLA status',
                     'elapsed_seconds': 'Elapsed'},
                 {'business_state': 220, 'sla_status': 140, 'elapsed_seconds': 110}, 'sla_status')
    p['fieldConfig']['overrides'].append({'matcher': {'id': 'byName', 'options': 'elapsed_seconds'}, 'properties': [
        {'id': 'unit', 'value': 's'}, {'id': 'decimals', 'value': 1}]})
    p = panel(overview, 'Evidence and target',
          "SELECT p.label AS milestone,p.target_seconds AS target_seconds,round(h.collector_age_seconds::numeric,1) AS collector_age_seconds,"
          "round(h.oldest_pending_seconds::numeric,1) AS pending_age_seconds,"
          "(SELECT count(*) FROM c_process_sla s WHERE $__timeFilter(s.admitted_at) AND s.policy_id=${policy:sqlstring} AND (${search:sqlstring}='' OR s.business_id=${search:sqlstring} OR s.operation_id=${search:sqlstring}) AND s.sla_status IN('Unknown','Stale')) AS uncertain_trades "
          'FROM c_sla_policy p CROSS JOIN c_evidence_health h WHERE p.policy_id=${policy:sqlstring}',
          0, 4, 24, 4, 'table', description='A stale collector or delayed journal can make current SLA status uncertain. Demo thresholds are configurable, not contractual SLAs.')
    _style_table(p, {'milestone': 'Selected milestone', 'target_seconds': 'Target (s)',
                     'collector_age_seconds': 'Collector age (s)', 'pending_age_seconds': 'Oldest pending (s)',
                     'uncertain_trades': 'Unknown / stale trades'})
    for field in ('collector_age_seconds', 'pending_age_seconds'):
        p['fieldConfig']['overrides'].append({'matcher': {'id': 'byName', 'options': field}, 'properties': [
            {'id': 'color', 'value': {'mode': 'thresholds'}},
            {'id': 'thresholds', 'value': {'mode': 'absolute', 'steps': [
                {'color': 'green', 'value': None}, {'color': 'yellow', 'value': 5}, {'color': 'red', 'value': 10}]}},
            {'id': 'custom.cellOptions', 'value': {'type': 'color-text'}}]})
    p['fieldConfig']['overrides'].append({'matcher': {'id': 'byName', 'options': 'uncertain_trades'}, 'properties': [
        {'id': 'color', 'value': {'mode': 'thresholds'}},
        {'id': 'thresholds', 'value': {'mode': 'absolute', 'steps': [
            {'color': 'blue', 'value': None}, {'color': 'yellow', 'value': 1}]}},
        {'id': 'custom.cellOptions', 'value': {'type': 'color-text'}}]})

    detail = dashboard('mocknet-c-process', 'Process detail', True)
    detail['tags'] = ['mocknet', 'business', 'observability']
    detail['refresh'] = '30s'
    detail['time'] = {'from': 'now-1h', 'to': 'now'}
    detail['description'] = ('Selected trade history from committed journal evidence. The progress strip and timeline are measured from trade receipt '
                             'and, like all evidence tables, are independent of the dashboard time range. '
                             'The terminal state extends to the latest collector observation, not to external settlement.')
    detail['links'] = [
        {'type': 'link', 'title': 'Back to overview',
         'url': '/d/mocknet-c-business?from=${overview_from}&to=${overview_to}&var-policy=${policy}'},
        {'type': 'link', 'title': 'Technical investigation',
         'url': '/d/mocknet-c-investigation?${__url_time_range}&var-operation=${operation}&var-search=${operation}'},
    ]
    detail['templating']['list'][-1]['label'] = 'Trade / operation'
    detail['templating']['list'][-1]['query'] = (
        "SELECT coalesce(business_id,operation_id)||' | '||business_state AS __text,operation_id AS __value "
        "FROM c_process_summary WHERE (${search:sqlstring}='' OR operation_id=${search:sqlstring} OR business_id=${search:sqlstring}) "
        'ORDER BY admitted_at DESC LIMIT 1000')
    detail['templating']['list'][-1]['refresh'] = 2
    detail['templating']['list'][0]['description'] = 'Find an older trade by its exact ID; selector results are independent of dashboard time.'
    detail['templating']['list'].extend([
        {'name': 'policy', 'type': 'custom', 'query': 'validation,matching,instructions', 'hide': 2,
         'current': {'text': 'instructions', 'value': 'instructions'}},
        {'name': 'overview_from', 'type': 'textbox', 'query': 'now-1h', 'hide': 2,
         'current': {'text': 'now-1h', 'value': 'now-1h'}},
        {'name': 'overview_to', 'type': 'textbox', 'query': 'now', 'hide': 2,
         'current': {'text': 'now', 'value': 'now'}},
    ])
    p = panel(detail, 'Selected trade',
              'SELECT business_id,business_state,received_at,retries,generated_outputs '
              'FROM c_process_summary WHERE operation_id=${operation:sqlstring}',
              0, 0, 24, 4, 'table', description='Instruction readiness confirms local output generation. External settlement is not measured.')
    _style_table(p, {'business_id': 'Trade', 'business_state': 'State', 'received_at': 'Received',
                     'generated_outputs': 'Instructions', 'retries': 'Retries'},
                 {'business_state': 200, 'received_at': 190, 'generated_outputs': 110, 'retries': 80}, 'business_state')
    p['options']['wrapText'] = True
    p['options']['cellHeight'] = 'md'
    # Milestone stepper: one tile per business milestone, left to right. Each value is
    # "<state>|<label>"; regex mappings colour the tile by state and display only the label.
    p = panel(detail, 'Process progress',
              "WITH p AS (SELECT * FROM c_process_summary WHERE operation_id=${operation:sqlstring}),"
              "st AS MATERIALIZED (SELECT * FROM c_process_stages(${operation:sqlstring})),"
              "targets AS (SELECT CASE policy_id WHEN 'validation' THEN 1 WHEN 'matching' THEN 2 WHEN 'instructions' THEN 4 END AS stage_order,"
              "target_seconds FROM c_sla_policy),"
              "steps AS (SELECT st.stage_order,CASE "
              "WHEN st.stage_order=4 AND st.readiness_at IS NOT NULL THEN "
              "CASE WHEN extract(epoch FROM st.readiness_at-p.received_at)>t.target_seconds THEN 'late|' ELSE 'done|' END"
              "||'T+'||c_format_duration(extract(epoch FROM st.readiness_at-p.received_at))"
              "||CASE WHEN extract(epoch FROM st.readiness_at-p.received_at)>t.target_seconds THEN ' · target '||c_format_duration(t.target_seconds) ELSE '' END"
              "||CASE WHEN st.status='Not required' THEN ' · none required' ELSE '' END "
              "WHEN st.status='Completed' THEN "
              "CASE WHEN extract(epoch FROM st.end_time-p.received_at)>t.target_seconds THEN 'late|' ELSE 'done|' END"
              "||'T+'||c_format_duration(extract(epoch FROM st.end_time-p.received_at))"
              "||CASE WHEN extract(epoch FROM st.end_time-p.received_at)>t.target_seconds THEN ' · target '||c_format_duration(t.target_seconds) ELSE '' END "
              "WHEN st.status='Observed open' THEN "
              "CASE WHEN extract(epoch FROM st.end_time-p.received_at)>t.target_seconds THEN 'late|Overdue · ' ELSE 'open|' END"
              "||CASE st.stage_order WHEN 2 THEN 'waiting for counterparty' ELSE 'in progress' END"
              "||' · '||c_format_duration(st.elapsed_ms/1000) "
              "WHEN st.status='Stale' THEN 'stale|Evidence stale · '||c_format_duration(st.elapsed_ms/1000) "
              "WHEN st.status='Rejected' THEN 'fail|Rejected at T+'||c_format_duration(extract(epoch FROM st.end_time-p.received_at)) "
              "WHEN st.status='Failed' THEN 'fail|Failed at T+'||c_format_duration(extract(epoch FROM st.end_time-p.received_at)) "
              "WHEN st.status='Missing evidence' THEN 'gap|Missing evidence' "
              "WHEN st.status='Not applicable' THEN 'skip|Not applicable' ELSE 'todo|Not reached' END AS step "
              "FROM st CROSS JOIN p LEFT JOIN targets t USING(stage_order)) "
              "SELECT CASE WHEN p.received_at IS NOT NULL THEN 'done|T+0' ELSE 'gap|Missing evidence' END AS \"Received\","
              "(SELECT step FROM steps WHERE stage_order=1) AS \"Validated\","
              "(SELECT step FROM steps WHERE stage_order=2) AS \"Matched\","
              "(SELECT step FROM steps WHERE stage_order=3) AS \"Netted\","
              "(SELECT step FROM steps WHERE stage_order=4) AS \"Instructions ready\" FROM p",
              0, 4, 24, 4, 'stat',
              description='Business milestones in order. T+ is elapsed time from trade receipt to the milestone. '
                          'Green: on time. Orange: reached late, or still open past its demo SLA target. Blue: open, with time waited so far '
                          '(up to the latest collector observation). Red: rejected or failed. Purple: evidence missing.')
    p['options'] = {'reduceOptions': {'calcs': ['lastNotNull'], 'fields': '/.*/', 'values': False},
                    'orientation': 'vertical', 'textMode': 'value_and_name', 'colorMode': 'background_solid',
                    'graphMode': 'none', 'justifyMode': 'center', 'wideLayout': True,
                    'text': {'titleSize': 13, 'valueSize': 16}}
    p['fieldConfig']['defaults'] = {'color': {'mode': 'fixed', 'fixedColor': 'transparent'}, 'noValue': 'Not recorded',
        'mappings': [{'type': 'regex', 'options': {'pattern': '^' + state + r'\|(.*)$', 'result': {'text': '$1', 'color': color}}}
                     for state, color in [('done', 'green'), ('late', 'orange'), ('open', 'blue'), ('stale', 'yellow'), ('fail', 'red'),
                                          ('gap', 'purple'), ('skip', '#6e7079'), ('todo', '#3a3d45')]]}
    # Gantt: one horizontal bar per stage on a time-since-receipt axis, independent of the
    # dashboard time range. "Start" is a transparent offset; the visible segment is coloured
    # by stage evidence. Dashed lines mark the demo SLA targets from c_sla_policy.
    p = panel(detail, 'Process timeline',
              "WITH st AS MATERIALIZED (SELECT * FROM c_process_stages(${operation:sqlstring})),"
              "origin AS (SELECT coalesce((SELECT received_at FROM c_process_summary WHERE operation_id=${operation:sqlstring}),"
              "(SELECT min(start_time) FROM st)) AS t0),"
              "targets AS (SELECT CASE policy_id WHEN 'validation' THEN 1 WHEN 'matching' THEN 2 WHEN 'instructions' THEN 4 END AS stage_order,"
              "target_seconds FROM c_sla_policy),"
              "bars AS (SELECT st.*,extract(epoch FROM st.start_time-o.t0) AS offset_s,"
              "CASE WHEN st.end_time>=st.start_time THEN extract(epoch FROM st.end_time-st.start_time) END AS duration_s,"
              "coalesce(extract(epoch FROM coalesce(st.readiness_at,st.end_time)-o.t0)>t.target_seconds,false) AS late "
              "FROM st CROSS JOIN origin o LEFT JOIN targets t USING(stage_order)) "
              "SELECT stage_order||'. '||CASE stage WHEN 'Counterparty matching wait' THEN 'Counterparty matching' ELSE stage END||'  ·  '||"
              "CASE WHEN status IN('Observed open','Stale') THEN 'open '||c_format_duration(duration_s) "
              "WHEN status='Rejected' THEN 'rejected after '||c_format_duration(duration_s) "
              "WHEN status='Failed' THEN 'failed after '||c_format_duration(duration_s) "
              "WHEN duration_s IS NOT NULL THEN c_format_duration(duration_s) ELSE lower(status) END AS stage,"
              "CASE WHEN duration_s IS NOT NULL THEN greatest(offset_s,0) END AS \"Start\","
              "CASE WHEN status='Completed' AND NOT late THEN duration_s END AS \"On time\","
              "CASE WHEN status IN('Completed','Observed open') AND late THEN duration_s END AS \"Past SLA target\","
              "CASE WHEN status='Observed open' AND NOT late THEN duration_s END AS \"In progress\","
              "CASE WHEN status='Stale' THEN duration_s END AS \"Stale evidence\","
              "CASE WHEN status IN('Rejected','Failed') THEN duration_s END AS \"Rejected / failed\","
              "NULL::double precision AS \"- - - SLA targets: " + ' · '.join(f'{t} s' for t in SLA_TARGETS_SECONDS) + "\" "
              "FROM bars ORDER BY stage_order",
              0, 8, 24, 8, 'barchart', 's',
              description='Each bar spans a stage from its first to its closing committed evidence, measured from trade receipt (T+0). '
                          'Matching includes counterparty wait; instruction generation can overlap netting. Open stages end at the latest '
                          'collector observation. Dashed lines: demo SLA targets (validation 5 s, matching 30 s, instruction readiness 60 s). '
                          'Stages lasting milliseconds appear as thin marks; the label carries the exact duration.')
    p['options'] = {'orientation': 'horizontal', 'xField': 'stage', 'stacking': 'normal', 'showValue': 'never',
                    'barWidth': 0.6, 'groupWidth': 0.7, 'barRadius': 0.1, 'fullHighlight': False,
                    'xTickLabelRotation': 0, 'xTickLabelMaxLength': 0,
                    'legend': {'showLegend': True, 'displayMode': 'list', 'placement': 'bottom'},
                    'tooltip': {'mode': 'multi', 'sort': 'none'}}
    p['fieldConfig']['defaults'] = {
        'unit': 's', 'min': 0, 'decimals': 1, 'color': {'mode': 'fixed', 'fixedColor': 'green'},
        'thresholds': {'mode': 'absolute', 'steps': [{'color': 'transparent', 'value': None}] +
                       [{'color': '#8e8e8e', 'value': target} for target in SLA_TARGETS_SECONDS]},
        'custom': {'fillOpacity': 85, 'lineWidth': 0, 'gradientMode': 'none', 'axisPlacement': 'auto',
                   'axisLabel': 'Time since trade received', 'axisSoftMin': 0, 'axisGridShow': True,
                   'thresholdsStyle': {'mode': 'dashed'}, 'hideFrom': {'legend': False, 'tooltip': False, 'viz': False}}}
    p['fieldConfig']['overrides'].append({'matcher': {'id': 'byName', 'options': 'Start'}, 'properties': [
        {'id': 'color', 'value': {'mode': 'fixed', 'fixedColor': 'transparent'}}, {'id': 'custom.fillOpacity', 'value': 0},
        {'id': 'custom.hideFrom', 'value': {'legend': True, 'tooltip': True, 'viz': False}}]})
    p['fieldConfig']['overrides'].append({'matcher': {'id': 'byRegexp', 'options': '^- - - SLA targets.*'}, 'properties': [
        {'id': 'color', 'value': {'mode': 'fixed', 'fixedColor': '#8e8e8e'}}, {'id': 'custom.hideFrom', 'value': {'legend': False, 'tooltip': True, 'viz': False}}]})
    for name, color in [('On time', 'green'), ('Past SLA target', 'orange'), ('In progress', 'blue'),
                        ('Stale evidence', 'yellow'), ('Rejected / failed', 'red')]:
        p['fieldConfig']['overrides'].append({'matcher': {'id': 'byName', 'options': name}, 'properties': [
            {'id': 'color', 'value': {'mode': 'fixed', 'fixedColor': color}}]})
    p = panel(detail, 'Stage timing',
              "SELECT stage,status,start_time,end_time,round(elapsed_ms::numeric,1) AS elapsed_ms,"
              "coalesce(retries::text,CASE WHEN stage_order=4 THEN 'See netting' ELSE 'Not reached' END) AS retries,"
              "max(readiness_at) OVER () AS readiness_at "
              'FROM c_process_stages(${operation:sqlstring}) ORDER BY stage_order',
              0, 16, 24, 7, 'table',
              description='Milliseconds between recorded evidence points, not handler CPU time. Open and stale rows end at collector observation. Readiness requires Netted plus all required GENERATED instructions; it may follow the generation interval. Blank intervals have no supported duration.')
    _style_table(p, {'stage': 'Stage', 'status': 'Evidence', 'start_time': 'From', 'end_time': 'To',
                     'elapsed_ms': 'Duration', 'retries': 'Retries', 'readiness_at': 'Process ready at'},
                 {'status': 140, 'start_time': 175, 'end_time': 175,
                  'elapsed_ms': 115, 'retries': 115, 'readiness_at': 175}, 'status')
    for name in ('start_time', 'end_time', 'readiness_at'):
        p['fieldConfig']['overrides'].append({'matcher': {'id': 'byName', 'options': name}, 'properties': [
            {'id': 'unit', 'value': 'time:HH:mm:ss.SSS'}]})
    p['fieldConfig']['overrides'].append({'matcher': {'id': 'byName', 'options': 'readiness_at'}, 'properties': [
        {'id': 'noValue', 'value': 'Not ready'}]})
    p['fieldConfig']['overrides'].append({'matcher': {'id': 'byName', 'options': 'elapsed_ms'}, 'properties': [
        {'id': 'unit', 'value': 'ms'}, {'id': 'decimals', 'value': 1}]})
    p = panel(detail, 'SLA milestones',
              'SELECT label,target_seconds,round(elapsed_seconds::numeric,2) AS elapsed_seconds,sla_status,received_at,finished_at '
              'FROM c_process_sla WHERE operation_id=${operation:sqlstring} ORDER BY target_seconds',
              0, 23, 24, 6, 'table', description='Demo targets: validation 5s, matching 30s, instruction readiness 60s. Elapsed starts at receipt and includes waits. Unknown, stale, and excluded are not success.')
    _style_table(p, {'label': 'Milestone', 'target_seconds': 'Target (s)', 'elapsed_seconds': 'Actual / observed (s)',
                     'sla_status': 'Status', 'received_at': 'Received', 'finished_at': 'Completed'},
                 {'label': 230, 'sla_status': 140}, 'sla_status')
    p = panel(detail, 'Business events',
              "SELECT at AS event_time,milestone,CASE WHEN baseline THEN 'Imported current state; time unknown' ELSE 'Committed journal event' END AS evidence "
              'FROM c_process_events WHERE operation_id=${operation:sqlstring} ORDER BY sequence LIMIT 200',
              0, 29, 24, 9, 'table', description='Retained committed events, including retries and output generation, independent of the selected time window.')
    _style_table(p, {'event_time': 'Time', 'milestone': 'Milestone', 'evidence': 'Evidence'},
                 {'event_time': 180, 'milestone': 240, 'evidence': 280})
    row = {'id': len(detail['panels']) + 1, 'title': 'Queue attempts and matched legs', 'type': 'row',
           'collapsed': True, 'gridPos': {'x': 0, 'y': 38, 'w': 24, 'h': 1}, 'panels': []}
    detail['panels'].append(row)
    p = panel(detail, 'Queue waits and retries',
              "SELECT a.operation_id,a.stage,a.attempt,a.started_at,a.finished_at,a.wait_seconds,a.duration_seconds,a.outcome,a.reason "
              "FROM c_attempt a WHERE a.operation_id=${operation:sqlstring} OR a.operation_id IN "
              '(SELECT related_operation_id FROM c_operation_links WHERE operation_id=${operation:sqlstring}) ORDER BY a.started_at LIMIT 200',
              0, 39, 24, 8, 'table', description='Worker attempts for this trade and its matched leg. Queue wait and attempt duration are distinct.')
    _style_table(p, {'operation_id': 'Operation', 'stage': 'Stage', 'attempt': 'Attempt', 'started_at': 'Started',
                     'finished_at': 'Finished', 'wait_seconds': 'Queue wait (s)', 'duration_seconds': 'Attempt (s)',
                     'outcome': 'Outcome', 'reason': 'Reason'})
    row['panels'].append(detail['panels'].pop())
    p = panel(detail, 'Recorded event fields',
              'SELECT at AS event_time,sequence,milestone,kind,entity_id,event_id,operation_id,baseline,body::text AS record '
              'FROM c_process_events WHERE operation_id=${operation:sqlstring} ORDER BY sequence',
              0, 47, 24, 10, 'table', description='All retained business event fields for this trade. The JSON record contains the collected source fields; absent source values are not invented.')
    _style_table(p, {'event_time': 'Time', 'sequence': 'Sequence', 'milestone': 'Milestone',
                     'kind': 'Source record', 'entity_id': 'Entity ID', 'event_id': 'Event ID',
                     'operation_id': 'Operation', 'baseline': 'Imported baseline', 'record': 'Recorded fields'})
    p['options']['wrapText'] = True
    row['panels'].append(detail['panels'].pop())
    return overview, detail

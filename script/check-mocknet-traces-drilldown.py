#!/usr/bin/env python3
"""Validate TraceQL metrics used by Grafana Traces Drilldown, separately from trace lookup."""
import json
from pathlib import Path
import time
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / '.bootstrap/observability/approach-a/drilldown-audit'

def main():
    end = int(time.time())
    queries = {
        'root_rate': '{nestedSetParent<0 && true && resource.service.name != nil} | rate() by(resource.service.name)',
        'all_spans_rate': '{resource.service.name="mocknet"} | rate()',
        'error_rate': '{nestedSetParent<0 && status=error} | rate()',
        'duration_histogram': '{nestedSetParent<0} | histogram_over_time(duration)',
        'duration_p90': '{nestedSetParent<0} | quantile_over_time(duration,0.9)',
    }
    results = []
    for name, query in queries.items():
        url = 'http://localhost:3200/api/metrics/query_range?' + urllib.parse.urlencode({
            'q': query, 'start': end-1800, 'end': end, 'step': '15s'})
        with urllib.request.urlopen(url, timeout=30) as response:
            data = json.load(response)
        series = data.get('series', [])
        positive = sum(float(sample.get('value', 0)) > 0 for s in series for sample in s.get('samples', []))
        if name != 'error_rate':
            assert positive > 0, f'{name}: no positive data; run the local feed and allow the generator to ingest traces'
        results.append({'query':name, 'expression':query, 'series':len(series), 'positiveSamples':positive})
    # The datasource API is a second boundary beyond direct Tempo queries.
    payload={'from':str((end-1800)*1000), 'to':str(end*1000), 'queries':[{
        'refId':'A', 'datasource':{'uid':'mocknet-tempo','type':'tempo'},
        'queryType':'traceql', 'metricsQueryType':'range', 'query':queries['root_rate'],
        'step':'15s', 'intervalMs':15000, 'maxDataPoints':200}]}
    request=urllib.request.Request('http://localhost:3300/api/ds/query',data=json.dumps(payload).encode(),headers={'Content-Type':'application/json'})
    with urllib.request.urlopen(request,timeout=30) as response:
        result=json.load(response)['results']['A']
    assert not result.get('error'), result
    assert result.get('frames'), 'Grafana returned no metric frames'
    output={'passed':True, 'checkedAt':end, 'queries':results, 'grafanaFrames':len(result['frames'])}
    OUT.mkdir(parents=True,exist_ok=True)
    (OUT/'after.json').write_text(json.dumps(output,indent=2)+'\n')
    print(json.dumps(output,indent=2))

if __name__ == '__main__':
    main()

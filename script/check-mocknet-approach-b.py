#!/usr/bin/env python3
"""Validate B's Grafana queries and JVM-only data boundary."""
import json,pathlib,time,urllib.request,urllib.parse,urllib.error
ROOT=pathlib.Path(__file__).resolve().parents[1]; STATE=ROOT/'.bootstrap/observability/approach-b'
def get(url):
 with urllib.request.urlopen(url,timeout=30) as r:return json.load(r)
def prom(q):return get('http://localhost:19091/api/v1/query?'+urllib.parse.urlencode({'query':q}))['data']['result']
def main():
 results=[]
 for file in (ROOT/'observability/approach-b/grafana').glob('*.json'):
  d=json.loads(file.read_text()); assert d['annotations']['list']==[]
  for p in d['panels']:
   assert p['datasource']['uid']=='mocknet-b-metrics'
   for t in p['targets']:
    q=t['expr'];assert 'jvm_' in q and all(x not in q for x in ['mocknet_queue','mocknet_processing','http_server','db_client','traces_'])
    for name in ['environment','service','version','app_instance']:q=q.replace('$'+name,'.*')
    q=q.replace('$__rate_interval','1m')
    body={'from':str(int((time.time()-900)*1000)),'to':str(int(time.time()*1000)),'queries':[{**t,'expr':q,'datasource':p['datasource'],'intervalMs':5000,'maxDataPoints':300}]}
    req=urllib.request.Request('http://localhost:3300/api/ds/query',data=json.dumps(body).encode(),headers={'Content-Type':'application/json'})
    with urllib.request.urlopen(req,timeout=30) as r:data=json.load(r)
    errors=[v['error'] for v in data.get('results',{}).values() if v.get('error')]; assert not errors,errors
    frames=[f for v in data.get('results',{}).values() for f in v.get('frames',[])]
    results.append({'dashboard':d['uid'],'panel':p['title'],'ref':t['refId'],'frames':len(frames)})
 # Prometheus's own scrape bookkeeping is not application telemetry and is not used by B panels.
 names=get('http://localhost:19091/api/v1/label/__name__/values')['data']
 unexpected=[n for n in names if not n.startswith(('jvm_','scrape_')) and n not in ['up','target_info']]
 assert not unexpected,unexpected
 data=prom('{__name__=~"jvm_.+"}'); assert data,'No live JVM metrics'
 assert all(x['metric'].get('otel_scope_name')=='io.opentelemetry.runtime-telemetry' and x['metric'].get('service_name')=='mocknet-b' for x in data)
 protocols={}
 for signal in ['traces','logs']:
  req=urllib.request.Request('http://localhost:14328/v1/'+signal,data=b'{}',headers={'Content-Type':'application/json'})
  try:
   urllib.request.urlopen(req,timeout=5);raise AssertionError('B unexpectedly accepts '+signal)
  except urllib.error.HTTPError as e:protocols[signal]=e.code;assert e.code==404
 result={'at':time.time(),'queries':results,'metricNames':names,'liveJvmSeries':len(data),'unexpectedMetrics':unexpected,'unsupportedSignalEndpoints':protocols}
 STATE.mkdir(parents=True,exist_ok=True);(STATE/'dashboard-validation.json').write_text(json.dumps(result,indent=2)+'\n')
 print(json.dumps({'queries':len(results),'emptyQueries':[r for r in results if not r['frames']],'liveJvmSeries':len(data),'unexpectedMetrics':unexpected,'signalEndpoints':protocols},indent=2))
if __name__=='__main__':main()

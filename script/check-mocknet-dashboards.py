#!/usr/bin/env python3
import json,pathlib,time,urllib.request,urllib.parse,re
ROOT=pathlib.Path(__file__).resolve().parents[1]
BASE='http://localhost:3300'
def request(path,data=None):
 r=urllib.request.Request(BASE+path,data=json.dumps(data).encode() if data is not None else None,headers={'Content-Type':'application/json'})
 try:
  with urllib.request.urlopen(r,timeout=30) as x:return json.load(x)
 except urllib.error.HTTPError as e:return {'error':e.read().decode()}
def sql(q):
 d=request('/api/ds/query',{'from':str(int((time.time()-3600)*1000)),'to':str(int(time.time()*1000)),'queries':[{'refId':'A','datasource':{'uid':'mocknet-operations','type':'grafana-postgresql-datasource'},'rawSql':q,'format':'table'}]})
 if 'error' in d:raise AssertionError(d)
 r=d['results']['A']
 if r.get('error'):raise AssertionError(r)
 rows=[]
 for f in r.get('frames',[]):
  names=[x['name'] for x in f['schema']['fields']]
  rows.extend(dict(zip(names,x)) for x in zip(*f['data']['values']))
 return rows
if __name__=='__main__':
 results=[]
 traces=sql("SELECT trace_id FROM support.operations WHERE trace_id IS NOT NULL AND accepted_at<now()-interval '30 seconds' ORDER BY accepted_at DESC LIMIT 1")
 assert traces,'A recorded operation is required to validate the native trace panel'
 trace_id=traces[0]['trace_id']
 for p in (ROOT/'observability/grafana/dashboards').glob('*.json'):
  d=json.loads(p.read_text())
  for panel in d['panels']:
   for q in panel.get('targets',[]):
    if not panel.get('datasource'):continue
    q={**q,'datasource':panel['datasource'],'intervalMs':5000,'maxDataPoints':300}
    for key in ['rawSql','expr','query']:
     if key not in q:continue
     x=q[key].replace('${trace_id:doublequote}', '""').replace('${business_id:doublequote}', '""').replace('${business_id:sqlstring}',"''").replace('${business_id:regex}','').replace('${trace_id:regex}','').replace('${stage:regex}','.*')
     for v in ['environment','service','version','app_instance','stage']:x=x.replace('$'+v,'.*')
     x=x.replace('$__rate_interval','1m').replace('$traceql','{ resource.service.name = "mocknet" && name = "QueueMessage.process" }')
     if '$trace_id' in x: x=x.replace('$trace_id',trace_id)
     q[key]=x
    # Mirror the installed Tempo frontend's hexadecimal-ID normalization.
    if panel['type']=='traces':q['queryType']='traceId'
    r=request('/api/ds/query',{'from':str(int((time.time()-3600)*1000)),'to':str(int(time.time()*1000)),'queries':[q]})
    errors=[v.get('error') for v in r.get('results',{}).values() if v.get('error')]
    if r.get('error'):errors.append(r['error'])
    if panel['type']=='traces' and not any(f.get('data',{}).get('values') for v in r.get('results',{}).values() for f in v.get('frames',[])):
     errors.append('Selected trace returned no data')
    results.append({'dashboard':d['uid'],'panel':panel['title'],'errors':errors,'frames':sum(len(v.get('frames',[])) for v in r.get('results',{}).values())})
 print(json.dumps(results,indent=2))
 assert not any(r['errors'] for r in results),'Dashboard query errors'

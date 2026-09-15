#!/usr/bin/env python3
"""Validate provisioned C queries and the report-only datasource boundary."""
import json,pathlib,re,time,urllib.request
import psycopg
ROOT=pathlib.Path(__file__).resolve().parents[1];STATE=ROOT/'.bootstrap/observability/approach-c'
def main():
 secret=dict(x.split('=',1) for x in (ROOT/'.bootstrap/observability/grafana.env').read_text().splitlines())
 with psycopg.connect(host='127.0.0.1',port=15452,dbname='telemetry_c',user='grafana_c',password=secret['MOCKNET_READER_PASSWORD']) as db:
  op=db.execute("select operation_id from c_operations where failed_messages>0 order by admitted_at desc limit 1").fetchone()[0]
  assert not db.execute("select has_database_privilege(current_user,'mocknet_c','CONNECT')").fetchone()[0]
  assert db.execute("show default_transaction_read_only").fetchone()[0]=='on'
 results=[]
 for file in (ROOT/'observability/approach-c/grafana').glob('*.json'):
  d=json.loads(file.read_text())
  for p in d['panels']:
   assert p['datasource']['uid']=='mocknet-c-reporting'
   q=p['targets'][0]['rawSql']
   assert not re.search(r'\b(queue_messages|processing_attempts|trades|support\.)\b',q.replace("kind='queue_messages'",''))
   for name,value in [('stage','.*'),('operation',op),('search','')]: q=q.replace('${'+name+':sqlstring}',"'"+value+"'")
   body={'from':str(int((time.time()-1800)*1000)),'to':str(int(time.time()*1000)), 'queries':[{**p['targets'][0],'rawSql':q,'datasource':p['datasource'],'intervalMs':5000,'maxDataPoints':300}]}
   request=urllib.request.Request('http://localhost:3302/api/ds/query',data=json.dumps(body).encode(),headers={'Content-Type':'application/json'})
   with urllib.request.urlopen(request,timeout=30) as response: result=json.load(response)
   errors=[v['error'] for v in result['results'].values() if v.get('error')]
   assert not errors,(p['title'],errors)
   frames=[f for v in result['results'].values() for f in v.get('frames',[])]
   if p['type']=='traces':
    assert len(frames)==1, 'Expected one reconstructed operation frame'
    frame=frames[0]; fields={f['name']:f['type'] for f in frame['schema']['fields']}
    assert all(fields.get(n)=='string' for n in ['traceID','spanID','parentSpanID','operationName','serviceName'])
    assert fields.get('startTime')=='number' and fields.get('duration')=='number'
    names=[f['name'] for f in frame['schema']['fields']]
    rows=[dict(zip(names,r)) for r in zip(*frame['data']['values'])]
    assert rows and sum(r['parentSpanID'] is None for r in rows)==1
    ids={r['spanID'] for r in rows}; assert len(ids)==len(rows)
    assert all(r['parentSpanID'] is None or r['parentSpanID'] in ids for r in rows)
    assert all(r['duration']>=0 and isinstance(r['tags'],list) and isinstance(r['warnings'],list) for r in rows)
   results.append({'dashboard':d['uid'],'panel':p['title'],'frames':len(frames)})
 out={'at':time.time(),'operation':op,'queries':results,'reportReaderCannotConnectToApplicationDatabase':True}
 (STATE/'dashboard-validation.json').write_text(json.dumps(out,indent=2)+'\n')
 print(json.dumps(out,indent=2))
if __name__=='__main__':main()

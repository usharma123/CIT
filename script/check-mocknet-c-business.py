#!/usr/bin/env python3
"""Check business milestones and SLA edge cases in rolled-back reporting fixtures."""
import datetime as dt
import json
from pathlib import Path
import uuid
import psycopg
from psycopg.types.json import Jsonb

ROOT=Path(__file__).resolve().parents[1]
secret=dict(x.split('=',1) for x in (ROOT/'.bootstrap/observability/grafana.env').read_text().splitlines())
base=dt.datetime.now(dt.timezone.utc)-dt.timedelta(seconds=100)
checks=[]
with psycopg.connect(host='127.0.0.1',port=15452,dbname='telemetry_c',user='mocknet_c',password=secret['MOCKNET_DB_PASSWORD']) as db:
    db.execute('SET LOCAL ROLE c_reporter')
    db.execute('SET LOCAL jit=off')
    db.execute('UPDATE collector_status SET at=%s,oldest_pending_seconds=0 WHERE id=1',(base+dt.timedelta(seconds=100),))
    sequence=1000000000000
    def event(op,kind,entity,t,body):
        global sequence
        sequence+=1
        db.execute('INSERT INTO evidence(event_id,source,at,sequence,kind,entity_id,operation_id,body) VALUES(%s,\'mq_journal\',%s,%s,%s,%s,%s,%s)',
            (str(uuid.uuid4()),base+dt.timedelta(seconds=t),sequence,kind,entity,op,Jsonb(body)))
    def process(validation=1,match=2,finish=5,reject=False,baseline=False,failed=False):
        op=str(uuid.uuid4()); entity=str(uuid.uuid4());queue=str(uuid.uuid4())
        queuebody={'id':queue,'queue_name':'INGESTION','status':'NEW','attempts':0,'created_at':base.isoformat(),'operation_id':op,'business_id':'fixture-'+op}
        event(op,'queue_messages',queue,0,queuebody)
        event(op,'queue_messages',queue,1,{**queuebody,'status':'FAILED' if failed else 'COMPLETED','outcome':'rejected' if reject else 'completed'})
        if validation is not None:
            event(op,'trades',entity,validation,{'status':'REJECTED' if reject else 'VALIDATED','baseline':baseline})
        if match is not None and not reject:
            event(op,'trades',entity,match,{'status':'MATCHED'})
        if finish is not None and not reject:
            for n in range(2):
                event(op,'netting_sets',op+str(n),finish-2,{'netting_set_id':op+str(n),'instruction_required':True})
            event(op,'trades',entity,finish-1,{'status':'NETTED'})
            for n in range(2):
                event(op,'settlement_instructions',op+'i'+str(n),finish,{'netting_set_id':op+str(n),'status':'GENERATED'})
        return op
    def statuses(op):
        return dict(db.execute('SELECT policy_id,sla_status FROM c_process_sla WHERE operation_id=%s',(op,)).fetchall())
    normal=process()
    assert set(statuses(normal).values())=={'Met'}
    checks.append('All milestones met')
    assert db.execute('SELECT extract(epoch FROM instructions_at-received_at) FROM c_process_summary WHERE operation_id=%s',(normal,)).fetchone()[0]==5
    checks.append('Receipt to final required instruction, not queue completion')
    slow=process(finish=65)
    assert statuses(slow)['instructions']=='Breached'
    assert statuses(process(finish=60))['instructions']=='Met'
    checks.append('Late completion breached; exact deadline met')
    rejected=process(reject=True)
    assert statuses(rejected)=={'validation':'Met','matching':'Excluded','instructions':'Excluded'}
    checks.append('Rejection only measured by validation policy')
    waiting=process(match=None,finish=None)
    assert statuses(waiting)['matching']=='Breached'
    checks.append('Counterparty wait included in SLA')
    db.execute('UPDATE collector_status SET at=%s WHERE id=1',(base+dt.timedelta(seconds=53),))
    assert statuses(waiting)['instructions']=='Stale'
    checks.append('Stale collector suppresses current SLA evaluation')
    db.execute('UPDATE collector_status SET at=%s WHERE id=1',(base+dt.timedelta(seconds=100),))
    imported=process(baseline=True)
    assert set(statuses(imported).values())=={'Unknown'}
    failed_ingestion=process(validation=None,match=None,finish=None,failed=True)
    ingestion_queue=db.execute("SELECT entity_id FROM evidence WHERE operation_id=%s AND kind='queue_messages' LIMIT 1",(failed_ingestion,)).fetchone()[0]
    event(failed_ingestion,'processing_attempts',str(uuid.uuid4()),2,
          {'queue_message_id':ingestion_queue,'queue_name':'INGESTION','outcome':'failed'})
    assert statuses(failed_ingestion)['instructions']=='Failed'
    checks.append('Imported history unknown; failed work is not success')
    first_leg=process(match=2,finish=None)
    second_leg=process(match=2,finish=None)
    event(first_leg,'matched_trades',str(uuid.uuid4()),3,{'related_operation_id':second_leg})
    netting_queue=str(uuid.uuid4())
    netting_body={'id':netting_queue,'queue_name':'NETTING','status':'NEW','attempts':0,
                  'created_at':(base+dt.timedelta(seconds=4)).isoformat(),'operation_id':first_leg}
    event(first_leg,'queue_messages',netting_queue,4,netting_body)
    event(first_leg,'queue_messages',netting_queue,7,{**netting_body,'status':'FAILED','attempts':1})
    event(first_leg,'processing_attempts',str(uuid.uuid4()),8,
          {'queue_message_id':netting_queue,'queue_name':'NETTING','outcome':'failed'})
    event(first_leg,'processing_attempts',str(uuid.uuid4()),6,
          {'queue_message_id':netting_queue,'queue_name':'NETTING','outcome':'retried'})
    for leg in (first_leg,second_leg):
        assert db.execute('SELECT business_state FROM c_process_summary WHERE operation_id=%s',(leg,)).fetchone()[0]=='Failed'
        assert statuses(leg)=={'validation':'Met','matching':'Met','instructions':'Failed'}
    assert db.execute("SELECT count(*) FROM c_process_events WHERE operation_id=%s AND milestone='Processing failed'",(second_leg,)).fetchone()[0]==1
    checks.append('Committed NETTING failure ends both matched legs; completed milestones remain met')
    partial=process(finish=None)
    event(partial,'netting_sets',partial+'n0',3,{'netting_set_id':partial+'n0','instruction_required':True})
    event(partial,'netting_sets',partial+'n1',3,{'netting_set_id':partial+'n1','instruction_required':True})
    trade_entity=db.execute("SELECT entity_id FROM evidence WHERE operation_id=%s AND kind='trades' LIMIT 1",(partial,)).fetchone()[0]
    event(partial,'trades',trade_entity,4,{'status':'NETTED'})
    event(partial,'settlement_instructions',partial+'i0',5,{'netting_set_id':partial+'n0','status':'GENERATED'})
    assert db.execute('SELECT instructions_at FROM c_process_summary WHERE operation_id=%s',(partial,)).fetchone()[0] is None
    checks.append('Partial instruction evidence cannot complete process')
    event(partial,'settlement_instructions',partial+'i1',6,{'netting_set_id':partial+'n1','status':'SENT'})
    assert statuses(partial)['instructions']=='Unknown'
    checks.append('Sent without generation history has unknown generation SLA')
    zero=process(finish=None)
    zero_entity=db.execute("SELECT entity_id FROM evidence WHERE operation_id=%s AND kind='trades' LIMIT 1",(zero,)).fetchone()[0]
    event(zero,'netting_sets',zero+'n',3,{'netting_set_id':zero+'n','instruction_required':False})
    event(zero,'trades',zero_entity,4,{'status':'NETTED'})
    assert statuses(zero)['instructions']=='Met'
    checks.append('Zero-net output explicitly requires no instruction')
    inverted=process(validation=1,match=0.5,finish=None)
    missing_start=process(validation=None,match=2,finish=None)
    related=str(uuid.uuid4())
    event(normal,'settlement_instructions',normal+'related',6,{'netting_set_id':normal+'0','status':'SENT','related_operation_id':related})
    assert db.execute('SELECT count(*) FROM c_process_events WHERE operation_id=%s',(related,)).fetchone()[0]==1
    assert db.execute('SELECT extract(epoch FROM instructions_at-received_at) FROM c_process_summary WHERE operation_id=%s',(normal,)).fetchone()[0]==5
    checks.append('Shared output links both legs; sending does not change generation SLA')
    normal_queue=db.execute("SELECT entity_id FROM evidence WHERE operation_id=%s AND kind='queue_messages' LIMIT 1",(normal,)).fetchone()[0]
    event(normal,'processing_attempts',normal+'retry',0.5,
          {'queue_message_id':normal_queue,'queue_name':'INGESTION','outcome':'retried'})
    event(normal,'processing_attempts',normal+'abandoned',0.75,
          {'queue_message_id':normal_queue,'queue_name':'INGESTION','outcome':'abandoned'})
    assert statuses(normal)['instructions']=='Met'
    assert db.execute('SELECT retries FROM c_process_summary WHERE operation_id=%s',(normal,)).fetchone()[0]==1
    assert {row[0] for row in db.execute('SELECT milestone FROM c_process_events WHERE operation_id=%s AND kind=\'processing_attempts\'',(normal,))}=={'Retry scheduled','Worker abandoned'}
    checks.append('Retry and abandoned attempts remain evidence without failing completed SLA')
    # Shift a waiting process so it is 53 seconds old at the live observation.
    db.execute("UPDATE evidence SET at=at+interval '47 seconds' WHERE operation_id=%s",(waiting,))
    assert statuses(waiting)['instructions']=='At risk'
    db.execute("UPDATE evidence SET at=at+interval '20 seconds' WHERE operation_id=%s",(waiting,))
    assert statuses(waiting)['instructions']=='In progress'
    checks.append('80 percent warning threshold and in-progress status')
    db.execute('UPDATE collector_status SET oldest_pending_seconds=30 WHERE id=1')
    assert statuses(waiting)['instructions']=='Stale'
    db.execute('UPDATE collector_status SET oldest_pending_seconds=0 WHERE id=1')
    checks.append('Old pending journal prevents a falsely current SLA')
    db.execute('RESET ROLE');db.execute('SET LOCAL ROLE grafana_c')
    timeline=db.execute('SELECT * FROM c_process_timeline(%s)',(normal,)).fetchall()
    assert [r[2] for r in timeline]==['Received','Waiting for counterparty','Matched','Netted','Instructions ready']
    assert all(r[1]>=r[0] for r in timeline)
    for leg,states in ((failed_ingestion,['Received','Failed']),
                       (first_leg,['Received','Waiting for counterparty','Matched','Failed']),
                       (second_leg,['Received','Waiting for counterparty','Matched','Failed'])):
        failed_timeline=db.execute('SELECT * FROM c_process_timeline(%s)',(leg,)).fetchall()
        assert [r[2] for r in failed_timeline]==states
        assert failed_timeline[-1][0]==base+dt.timedelta(seconds=2 if leg==failed_ingestion else 8)
        assert failed_timeline[-1][1]==base+dt.timedelta(seconds=100)
    checks.append('Failure begins at recorded attempt time on owning and linked legs')
    def stage_rows(op):
        return {r[1]:r for r in db.execute('SELECT * FROM c_process_stages(%s)',(op,))}
    measured=stage_rows(normal)
    assert list(measured)==['Validation','Counterparty matching wait','Netting','Instruction generation']
    assert [measured[name][4] for name in measured]==['Completed']*4
    assert [round(measured[name][5]) for name in measured]==[1000,1000,2000,2000]
    assert measured['Netting'][3]==base+dt.timedelta(seconds=4)
    assert measured['Instruction generation'][2]==base+dt.timedelta(seconds=3)
    assert measured['Instruction generation'][7]==base+dt.timedelta(seconds=5)
    assert all(row[3]<=base+dt.timedelta(seconds=5) for row in measured.values())
    checks.append('Four committed stage intervals overlap where output generation precedes netting completion; success has no collector tail')
    generated=json.loads((ROOT/'observability/approach-c/grafana/mocknet-c-process.json').read_text())
    def panel_rows(title,op):
        sql=next(p for p in generated['panels'] if p['title']==title)['targets'][0]['rawSql']
        return db.execute(sql.replace('${operation:sqlstring}','%s'),(op,)*sql.count('${operation:sqlstring}')).fetchall()
    received=db.execute('SELECT received_at FROM c_process_summary WHERE operation_id=%s',(normal,)).fetchone()[0]
    gantt={row[0].split('  ·  ')[0]:row for row in panel_rows('Process timeline',normal)}
    assert list(gantt)==['1. Validation','2. Counterparty matching','3. Netting','4. Instruction generation']
    for label,name in zip(gantt,measured):
        assert abs(float(gantt[label][1])-(measured[name][2]-received).total_seconds())<1e-6
        assert abs(float(gantt[label][2])*1000-measured[name][5])<1e-3 and all(v is None for v in gantt[label][3:])
    assert gantt['4. Instruction generation'][1]<gantt['3. Netting'][1]+gantt['3. Netting'][2]
    assert panel_rows('Process progress',normal)[0][0]=='done|T+0'
    assert all(step.startswith('done|T+') for step in panel_rows('Process progress',normal)[0])
    checks.append('Horizontal Gantt offsets and durations match committed stage intervals, including overlap; progress strip reaches every milestone')
    assert stage_rows(zero)['Instruction generation'][4]=='Not required'
    assert stage_rows(zero)['Instruction generation'][3] is None
    assert stage_rows(zero)['Instruction generation'][7]==base+dt.timedelta(seconds=4)
    checks.append('No-required instruction is a readiness milestone, not a fabricated bar')
    assert stage_rows(inverted)['Counterparty matching wait'][4]=='Missing evidence'
    assert stage_rows(inverted)['Counterparty matching wait'][3] is None
    assert stage_rows(missing_start)['Validation'][4]=='Missing evidence'
    assert stage_rows(missing_start)['Counterparty matching wait'][4]=='Missing evidence'
    assert stage_rows(imported)['Validation'][4]=='Missing evidence'
    checks.append('Inverted chronology, missing start, and imported partial history never become open elapsed work')
    assert stage_rows(first_leg)['Netting'][6]==1
    assert stage_rows(second_leg)['Netting'][6]==1
    assert stage_rows(second_leg)['Netting'][4]=='Failed'
    checks.append('Shared netting retry and terminal failure remain visible on both matched legs')
    open_wait=stage_rows(waiting)['Counterparty matching wait']
    assert open_wait[4]=='Observed open' and open_wait[3]==base+dt.timedelta(seconds=100)
    assert open_wait[5]==32000
    failed_validation=stage_rows(failed_ingestion)['Validation']
    assert failed_validation[4]=='Failed' and failed_validation[3]==base+dt.timedelta(seconds=2)
    assert all(stage_rows(rejected)[name][4]=='Not applicable'
               for name in ('Counterparty matching wait','Netting','Instruction generation'))
    checks.append('Open wait ends at collector observation; terminal failure and rejection stop at recorded evidence')
    db.execute('RESET ROLE');db.execute('SET LOCAL ROLE c_reporter')
    db.execute('UPDATE collector_status SET at=%s WHERE id=1',(base+dt.timedelta(seconds=80),))
    db.execute('RESET ROLE');db.execute('SET LOCAL ROLE grafana_c')
    stale_wait=stage_rows(waiting)['Counterparty matching wait']
    assert stale_wait[4]=='Stale' and stale_wait[3]==base+dt.timedelta(seconds=80)
    db.execute('RESET ROLE');db.execute('SET LOCAL ROLE c_reporter')
    db.execute('UPDATE collector_status SET at=%s WHERE id=1',(base+dt.timedelta(seconds=100),))
    db.execute('RESET ROLE');db.execute('SET LOCAL ROLE grafana_c')
    checks.append('Old collector marks observed waiting interval stale without promoting it to completion')
    assert db.execute('SELECT * FROM c_process_timeline(%s)',(str(uuid.uuid4()),)).fetchall()==[]
    checks.append('Read-only Grafana role; ordered intervals; unknown process empty')
    db.rollback()

# Latest-state projection must preserve replay ordering and roll back with evidence.
with psycopg.connect(host='127.0.0.1',port=15452,dbname='telemetry_c',user='mocknet_c',password=secret['MOCKNET_DB_PASSWORD']) as db:
    db.execute('SET LOCAL ROLE c_reporter')
    entity=str(uuid.uuid4()); operation=str(uuid.uuid4()); newest=str(uuid.uuid4())
    for event_id,seq,status in [(newest,200,'MATCHED'),(str(uuid.uuid4()),100,'VALIDATED')]:
        db.execute("INSERT INTO evidence(event_id,source,at,sequence,kind,entity_id,operation_id,body) VALUES(%s,'mq_journal',now(),%s,'trades',%s,%s,%s)",
                   (event_id,seq,entity,operation,Jsonb({'status':status})))
    assert db.execute("SELECT event_id,sequence,body->>'status' FROM c_journal_latest WHERE kind='trades' AND entity_id=%s",(entity,)).fetchone()==(newest,200,'MATCHED')
    db.execute("INSERT INTO evidence(event_id,source,at,sequence,kind,entity_id,operation_id,body) VALUES(%s,'mq_journal',now(),200,'trades',%s,%s,%s) ON CONFLICT DO NOTHING",
               (newest,entity,operation,Jsonb({'status':'MATCHED'})))
    assert db.execute('SELECT count(*) FROM c_journal_latest WHERE entity_id=%s',(entity,)).fetchone()[0]==1
    db.rollback()
    assert db.execute('SELECT count(*) FROM c_journal_latest WHERE entity_id=%s',(entity,)).fetchone()[0]==0
    checks.append('Latest journal projection preserves out-of-order replay, duplicate identity and rollback')

# Prove new source triggers are transactional and restrict their output fields.
with psycopg.connect(host='127.0.0.1',port=15452,dbname='mocknet_c',user='mocknet_c',password=secret['MOCKNET_DB_PASSWORD']) as db:
    nid=db.execute('INSERT INTO netting_sets(counterparty1,counterparty2,currency,net_amount,value_date,matched_trade_id,calculated_at) SELECT counterparty1,counterparty2,currency,net_amount,value_date,matched_trade_id,calculated_at FROM netting_sets LIMIT 1 RETURNING id').fetchone()[0]
    sid=db.execute('INSERT INTO settlement_instructions(netting_set_id,payer_party,receiver_party,currency,amount,status,generated_at) SELECT %s,payer_party,receiver_party,currency,amount,status,generated_at FROM settlement_instructions LIMIT 1 RETURNING id',(nid,)).fetchone()[0]
    rows=db.execute("SELECT sequence,body FROM c_mq_journal WHERE (kind='netting_sets' AND entity_id=%s) OR (kind='settlement_instructions' AND entity_id=%s)",(nid,sid)).fetchall()
    assert len(rows)==2 and all(r[1].get('related_operation_id') for r in rows)
    assert all(set(r[1])<={'id','netting_set_id','instruction_required','status','operation_id','related_operation_id','matched_trade_id'} for r in rows)
    seqs=[r[0] for r in rows];db.rollback()
    assert db.execute('SELECT count(*) FROM c_mq_journal WHERE sequence=ANY(%s)',(seqs,)).fetchone()[0]==0
    checks.append('Source output journals link both legs, exclude payloads and roll back with business writes')
result={'passed':True,'checks':checks,'fixturesRolledBack':True}
(ROOT/'.bootstrap/observability/approach-c/business-validation.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result,indent=2))

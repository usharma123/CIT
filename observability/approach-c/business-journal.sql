-- Business output metadata commits with the application transaction. No amounts or parties.
CREATE OR REPLACE FUNCTION c_capture_output() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE doc jsonb; matched_id bigint;
BEGIN
 IF TG_TABLE_NAME='netting_sets' THEN
  matched_id:=NEW.matched_trade_id;
  doc:=jsonb_build_object('id',NEW.id,'netting_set_id',NEW.id,'instruction_required',NEW.net_amount<>0);
 ELSE
  SELECT matched_trade_id INTO matched_id FROM netting_sets WHERE id=NEW.netting_set_id;
  doc:=jsonb_build_object('id',NEW.id,'netting_set_id',NEW.netting_set_id,'status',NEW.status);
  IF TG_OP='UPDATE' AND OLD.status=NEW.status THEN RETURN NEW; END IF;
 END IF;
 SELECT doc || jsonb_build_object('operation_id',a.operation_id,'related_operation_id',b.operation_id,'matched_trade_id',matched_id)
 INTO doc FROM matched_trades m JOIN trades a ON a.id=m.trade1id JOIN trades b ON b.id=m.trade2id WHERE m.id=matched_id;
 IF doc->>'operation_id' IS NOT NULL THEN
  INSERT INTO c_mq_journal(kind,entity_id,body) VALUES(TG_TABLE_NAME,NEW.id,doc);
 END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS c_netting_output_journal ON netting_sets;
CREATE TRIGGER c_netting_output_journal AFTER INSERT ON netting_sets FOR EACH ROW EXECUTE FUNCTION c_capture_output();
DROP TRIGGER IF EXISTS c_instruction_journal ON settlement_instructions;
CREATE TRIGGER c_instruction_journal AFTER INSERT OR UPDATE ON settlement_instructions FOR EACH ROW EXECUTE FUNCTION c_capture_output();
-- Existing records establish current state only. Their observation time is not their creation time.
INSERT INTO c_mq_journal(kind,entity_id,body)
 SELECT 'netting_sets',n.id,jsonb_build_object('id',n.id,'netting_set_id',n.id,'instruction_required',n.net_amount<>0,
 'operation_id',a.operation_id,'related_operation_id',b.operation_id,'matched_trade_id',m.id,'baseline',true)
 FROM netting_sets n JOIN matched_trades m ON m.id=n.matched_trade_id JOIN trades a ON a.id=m.trade1id JOIN trades b ON b.id=m.trade2id
 WHERE a.operation_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM c_mq_journal j WHERE j.kind='netting_sets' AND j.entity_id=n.id);
INSERT INTO c_mq_journal(kind,entity_id,body)
 SELECT 'settlement_instructions',s.id,jsonb_build_object('id',s.id,'netting_set_id',s.netting_set_id,'status',s.status,
 'operation_id',a.operation_id,'related_operation_id',b.operation_id,'matched_trade_id',m.id,'baseline',true)
 FROM settlement_instructions s JOIN netting_sets n ON n.id=s.netting_set_id JOIN matched_trades m ON m.id=n.matched_trade_id
 JOIN trades a ON a.id=m.trade1id JOIN trades b ON b.id=m.trade2id
 WHERE a.operation_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM c_mq_journal j WHERE j.kind='settlement_instructions' AND j.entity_id=s.id);

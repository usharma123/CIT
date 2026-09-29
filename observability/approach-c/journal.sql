-- Install before accepting traffic. Trigger inserts roll back with the queue mutation.
CREATE TABLE IF NOT EXISTS c_mq_journal (
  sequence bigserial PRIMARY KEY, at timestamptz NOT NULL DEFAULT clock_timestamp(),
  kind text NOT NULL, entity_id bigint NOT NULL, body jsonb NOT NULL,
  delivered_at timestamptz
);
CREATE INDEX IF NOT EXISTS c_journal_pending ON c_mq_journal(sequence) WHERE delivered_at IS NULL;
CREATE OR REPLACE FUNCTION c_capture_mq() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE doc jsonb; previous jsonb; fields text[];
BEGIN
  IF TG_TABLE_NAME = 'queue_messages' THEN
    fields := ARRAY['id','queue_name','status','attempts','created_at','available_at','claimed_at','completed_at','worker_name','operation_id','business_id','outcome'];
  ELSE
    fields := ARRAY['id','queue_message_id','operation_id','queue_name','claimed_at','finished_at','attempt_number','wait_seconds','processing_seconds','outcome','reason','worker','instance','service_version'];
  END IF;
  SELECT jsonb_object_agg(key,value) INTO doc FROM jsonb_each(to_jsonb(NEW)) WHERE key = ANY(fields);
  IF TG_OP = 'UPDATE' THEN
    SELECT jsonb_object_agg(key,value) INTO previous FROM jsonb_each(to_jsonb(OLD)) WHERE key = ANY(fields);
    IF doc = previous THEN RETURN NEW; END IF;
  END IF;
  INSERT INTO c_mq_journal(kind,entity_id,body) VALUES (TG_TABLE_NAME,NEW.id,doc);
  RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS c_queue_journal ON queue_messages;
CREATE TRIGGER c_queue_journal AFTER INSERT OR UPDATE ON queue_messages FOR EACH ROW EXECUTE FUNCTION c_capture_mq();
DROP TRIGGER IF EXISTS c_attempt_journal ON processing_attempts;
CREATE TRIGGER c_attempt_journal AFTER INSERT OR UPDATE ON processing_attempts FOR EACH ROW EXECUTE FUNCTION c_capture_mq();

-- Business state and matched-leg identities use the same committed journal.
-- Allowlisted metadata only: no trade payload, amounts or counterparties.
CREATE OR REPLACE FUNCTION c_capture_business() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE doc jsonb; previous jsonb;
BEGIN
 IF TG_TABLE_NAME='trades' THEN
  doc:=jsonb_build_object('id',NEW.id,'operation_id',NEW.operation_id,'status',NEW.status);
  IF TG_OP='UPDATE' THEN
   previous:=jsonb_build_object('id',OLD.id,'operation_id',OLD.operation_id,'status',OLD.status);
   IF doc=previous THEN RETURN NEW; END IF;
  END IF;
 ELSE
  SELECT jsonb_build_object('id',NEW.id,'operation_id',a.operation_id,'related_operation_id',b.operation_id)
   INTO doc FROM trades a JOIN trades b ON b.id=NEW.trade2id WHERE a.id=NEW.trade1id;
 END IF;
 IF doc->>'operation_id' IS NOT NULL THEN
  INSERT INTO c_mq_journal(kind,entity_id,body) VALUES(TG_TABLE_NAME,NEW.id,doc);
 END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS c_trade_journal ON trades;
CREATE TRIGGER c_trade_journal AFTER INSERT OR UPDATE ON trades FOR EACH ROW EXECUTE FUNCTION c_capture_business();
DROP TRIGGER IF EXISTS c_match_journal ON matched_trades;
CREATE TRIGGER c_match_journal AFTER INSERT ON matched_trades FOR EACH ROW EXECUTE FUNCTION c_capture_business();
INSERT INTO c_mq_journal(kind,entity_id,body)
 SELECT 'trades',t.id,jsonb_build_object('id',t.id,'operation_id',t.operation_id,'status',t.status,'baseline',true)
 FROM trades t WHERE t.operation_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM c_mq_journal j WHERE j.kind='trades' AND j.entity_id=t.id);
INSERT INTO c_mq_journal(kind,entity_id,body)
 SELECT 'matched_trades',m.id,jsonb_build_object('id',m.id,'operation_id',a.operation_id,'related_operation_id',b.operation_id,'baseline',true)
 FROM matched_trades m JOIN trades a ON a.id=m.trade1id JOIN trades b ON b.id=m.trade2id
 WHERE a.operation_id IS NOT NULL AND b.operation_id IS NOT NULL
 AND NOT EXISTS(SELECT 1 FROM c_mq_journal j WHERE j.kind='matched_trades' AND j.entity_id=m.id);

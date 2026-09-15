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

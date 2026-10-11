-- A message that cannot be processed now blocks the topic and is retried in place, rather than being parked as
-- failed and later purged, so a poison message is never silently dropped. Existing failed rows were already
-- unprocessable, so drop them.
DELETE FROM message_queue WHERE status = 'failed';

ALTER TABLE message_queue DROP CONSTRAINT message_queue_status_chk;

ALTER TABLE message_queue
    ADD CONSTRAINT message_queue_status_chk CHECK (status IN ('pending', 'claimed', 'processed'));

DROP INDEX message_queue_processed_idx;

-- Purge scan: processed rows past their retention window.
CREATE INDEX message_queue_processed_idx
    ON message_queue (processed_at)
    WHERE status = 'processed';

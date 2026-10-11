-- Restructure message_queue from a work queue into an append-only log, and track each consumer group's
-- position in message_consumer (Kafka-style offsets), so one topic can be consumed independently by several
-- processors. A message is only deleted once every consumer group has processed it.
CREATE TABLE message_consumer (
    topic       VARCHAR(128) NOT NULL,
    consumer_id VARCHAR(128) NOT NULL,
    last_offset BIGINT       NOT NULL DEFAULT 0,
    attempts    INTEGER      NOT NULL DEFAULT 0,
    last_error  TEXT,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT message_consumer_pk PRIMARY KEY (topic, consumer_id)
);

-- The existing solr-indexer consumer resumes from the last message it processed; anything left pending or
-- claimed is picked up again through its offset.
INSERT INTO message_consumer (topic, consumer_id, last_offset)
SELECT topic, 'solr-indexer', COALESCE(MAX(id) FILTER (WHERE status = 'processed'), 0)
FROM message_queue
GROUP BY topic;

DROP INDEX message_queue_pending_idx;
DROP INDEX message_queue_claimed_idx;
DROP INDEX message_queue_processed_idx;

ALTER TABLE message_queue DROP CONSTRAINT message_queue_status_chk;

ALTER TABLE message_queue
    DROP COLUMN status,
    DROP COLUMN attempts,
    DROP COLUMN last_error,
    DROP COLUMN claimed_at,
    DROP COLUMN processed_at;

-- Log read: the next message after a consumer's committed offset.
CREATE INDEX message_queue_topic_idx
    ON message_queue (topic, id);

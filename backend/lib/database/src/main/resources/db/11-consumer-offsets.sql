-- Turn message_queue into an append-only log (message_log) and track each consumer group's position in
-- message_consumer (Kafka-style offsets), so one topic can be consumed independently by several processors.
-- A message is only deleted once every consumer group has processed it.
CREATE TABLE message_consumer (
    topic       VARCHAR(128) NOT NULL,
    consumer_id VARCHAR(128) NOT NULL,
    last_offset BIGINT       NOT NULL DEFAULT 0,
    attempts    INTEGER      NOT NULL DEFAULT 0,
    last_error  TEXT,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT message_consumer_pk PRIMARY KEY (topic, consumer_id)
);

-- Discard the queue contents instead of migrating the current consumer position. Safe here because the queue is
-- idle at deploy time (all writes are admin-gated and the demo is static), so there is nothing unprocessed to
-- lose. Consumer groups register lazily at the latest offset on startup.
TRUNCATE message_queue;

ALTER TABLE message_queue RENAME TO message_log;
ALTER TABLE message_log RENAME CONSTRAINT message_queue_pk TO message_log_pk;
ALTER SEQUENCE message_queue_id_seq RENAME TO message_log_id_seq;

DROP INDEX message_queue_pending_idx;
DROP INDEX message_queue_claimed_idx;
DROP INDEX message_queue_processed_idx;

ALTER TABLE message_log DROP CONSTRAINT message_queue_status_chk;

ALTER TABLE message_log
    DROP COLUMN status,
    DROP COLUMN attempts,
    DROP COLUMN last_error,
    DROP COLUMN claimed_at,
    DROP COLUMN processed_at;

-- Log read: the next message after a consumer's committed offset.
CREATE INDEX message_log_topic_idx
    ON message_log (topic, id);

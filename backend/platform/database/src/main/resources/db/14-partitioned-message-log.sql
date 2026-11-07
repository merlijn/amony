-- Add partitions, message keys, and transaction IDs to message_log, and per-partition offsets and leases to
-- message_consumer, so multiple nodes can consume a topic concurrently while preserving per-key ordering and never
-- skipping out-of-order transaction commits.
TRUNCATE message_log;
TRUNCATE message_consumer;

ALTER TABLE message_log
    ADD COLUMN partition   INTEGER      NOT NULL DEFAULT 0,
    ADD COLUMN message_key VARCHAR(256) NOT NULL DEFAULT '',
    ADD COLUMN tx_id       XID8         NOT NULL DEFAULT pg_current_xact_id();

DROP INDEX message_log_topic_idx;

CREATE INDEX message_log_topic_partition_idx
    ON message_log (topic, partition, id);

ALTER TABLE message_consumer
    ADD COLUMN partition    INTEGER     NOT NULL DEFAULT 0,
    ADD COLUMN locked_until TIMESTAMPTZ,
    DROP CONSTRAINT message_consumer_pk,
    ADD CONSTRAINT message_consumer_pk PRIMARY KEY (topic, consumer_id, partition);

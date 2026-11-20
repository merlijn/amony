CREATE TABLE message_partition (
    topic                 VARCHAR(128) NOT NULL,
    partition             INTEGER      NOT NULL,
    last_allocated_offset BIGINT       NOT NULL,
    PRIMARY KEY (topic, partition)
);

ALTER TABLE message_log ADD COLUMN partition_offset BIGINT;

-- Preserve the meaning of existing consumer cursors, including offsets whose messages were already purged.
UPDATE message_log SET partition_offset = id;

INSERT INTO message_partition (topic, partition, last_allocated_offset)
SELECT topic, partition, max(last_offset)
FROM (
    SELECT topic, partition, max(partition_offset) AS last_offset FROM message_log GROUP BY topic, partition
    UNION ALL
    SELECT topic, partition, max(last_offset) FROM message_consumer GROUP BY topic, partition
) offsets
GROUP BY topic, partition;

ALTER TABLE message_log
    ALTER COLUMN partition_offset SET NOT NULL,
    DROP COLUMN tx_id;

DROP INDEX message_log_topic_partition_idx;

CREATE UNIQUE INDEX message_log_topic_partition_offset_idx ON message_log (topic, partition, partition_offset);

ALTER TABLE message_consumer ADD COLUMN lease_generation BIGINT NOT NULL DEFAULT 0;

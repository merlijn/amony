-- Store payloads as opaque bytes so a topic can carry formats other than JSON (for example Avro or Protobuf).
-- Existing JSON payloads are not converted: producers are frozen during rollout, so all delivery state is discarded
-- and consumers start from a clean log.
TRUNCATE message_log;
TRUNCATE message_partition;
TRUNCATE message_consumer;
TRUNCATE job_status;

ALTER TABLE message_log
    DROP COLUMN payload,
    ADD COLUMN payload BYTEA NOT NULL;

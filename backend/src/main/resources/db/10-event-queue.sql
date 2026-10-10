CREATE TABLE event_queue (
    id           BIGSERIAL    NOT NULL,
    topic        VARCHAR(128) NOT NULL,
    payload      JSONB        NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'pending',
    attempts     INTEGER      NOT NULL DEFAULT 0,
    last_error   TEXT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    claimed_at   TIMESTAMPTZ,
    processed_at TIMESTAMPTZ,
    CONSTRAINT event_queue_pk PRIMARY KEY (id),
    CONSTRAINT event_queue_status_chk CHECK (status IN ('pending', 'claimed', 'processed', 'failed'))
);

-- Ordered claim: the oldest pending event for a topic.
CREATE INDEX event_queue_pending_idx
    ON event_queue (topic, id)
    WHERE status = 'pending';

-- Recovery scan: events left claimed by a consumer that stopped.
CREATE INDEX event_queue_claimed_idx
    ON event_queue (topic, claimed_at)
    WHERE status = 'claimed';

-- Purge scan: processed and failed rows past their retention window.
CREATE INDEX event_queue_processed_idx
    ON event_queue (processed_at)
    WHERE status IN ('processed', 'failed');

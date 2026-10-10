CREATE TABLE event_outbox (
    id           BIGSERIAL    NOT NULL,
    topic        VARCHAR(128) NOT NULL,
    payload      JSONB        NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'pending',
    attempts     INTEGER      NOT NULL DEFAULT 0,
    last_error   TEXT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    claimed_at   TIMESTAMPTZ,
    processed_at TIMESTAMPTZ,
    CONSTRAINT event_outbox_pk PRIMARY KEY (id),
    CONSTRAINT event_outbox_status_chk CHECK (status IN ('pending', 'claimed', 'processed', 'failed'))
);

-- Ordered claim: the oldest pending event for a topic.
CREATE INDEX event_outbox_pending_idx
    ON event_outbox (topic, id)
    WHERE status = 'pending';

-- Recovery scan: events left claimed by a consumer that stopped.
CREATE INDEX event_outbox_claimed_idx
    ON event_outbox (topic, claimed_at)
    WHERE status = 'claimed';

-- Purge scan: processed and failed rows past their retention window.
CREATE INDEX event_outbox_processed_idx
    ON event_outbox (processed_at)
    WHERE status IN ('processed', 'failed');

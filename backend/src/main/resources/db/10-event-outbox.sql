-- Per-resource monotonic revision and tombstone flag. Kept in a separate table (rather than a column
-- on resources) so that hard-deleting a resource still lets its cascades fire normally and so that
-- resources queries need no deleted filter. The revision must outlive the resource row, hence there
-- is deliberately no foreign key to resources.
CREATE TABLE resource_revision (
    bucket_id   VARCHAR(64) NOT NULL,
    resource_id VARCHAR(64) NOT NULL,
    revision    BIGINT      NOT NULL DEFAULT 0,
    deleted     BOOLEAN     NOT NULL DEFAULT false,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT resource_revision_pk PRIMARY KEY (bucket_id, resource_id)
);

-- Purge scan for tombstones that are no longer needed.
CREATE INDEX resource_revision_deleted_idx
    ON resource_revision (updated_at)
    WHERE deleted;

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

-- Claim scan: pending work for a topic, oldest first.
CREATE INDEX event_outbox_pending_idx
    ON event_outbox (topic, created_at)
    WHERE status = 'pending';

-- TTL sweep: claimed work that has been sitting too long.
CREATE INDEX event_outbox_claimed_idx
    ON event_outbox (topic, claimed_at)
    WHERE status = 'claimed';

-- Purge scan: processed and failed rows past their retention window.
CREATE INDEX event_outbox_processed_idx
    ON event_outbox (processed_at)
    WHERE status IN ('processed', 'failed');

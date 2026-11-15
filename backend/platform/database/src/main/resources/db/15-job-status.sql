-- Producer-visible lifecycle of a job, kept separately from the append-only message log so a job's status
-- survives until the caller stops observing it (log rows are purged as soon as consumers pass them).
CREATE TABLE job_status (
    topic       VARCHAR(128) NOT NULL,
    job_id      VARCHAR(36)  NOT NULL,
    message_key VARCHAR(256) NOT NULL,
    state       VARCHAR(16)  NOT NULL DEFAULT 'queued',
    attempts    INTEGER      NOT NULL DEFAULT 0,
    last_error  TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT job_status_pk PRIMARY KEY (topic, job_id),
    CONSTRAINT job_status_state_chk CHECK (state IN ('queued', 'running', 'succeeded', 'failed'))
);

-- Claim scan: queued and running jobs.
CREATE INDEX job_status_state_idx
    ON job_status (topic, state, updated_at);

-- Retention sweep of terminal jobs.
CREATE INDEX job_status_purge_idx
    ON job_status (updated_at)
    WHERE state IN ('succeeded', 'failed');

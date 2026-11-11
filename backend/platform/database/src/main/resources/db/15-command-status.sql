-- Producer-visible lifecycle of a command, kept separately from the append-only message log so a command's status
-- survives until the caller stops observing it (log rows are purged as soon as consumers pass them).
CREATE TABLE command_status (
    topic       VARCHAR(128) NOT NULL,
    command_id  VARCHAR(36)  NOT NULL,
    message_key VARCHAR(256) NOT NULL,
    state       VARCHAR(16)  NOT NULL DEFAULT 'queued',
    attempts    INTEGER      NOT NULL DEFAULT 0,
    last_error  TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT command_status_pk PRIMARY KEY (topic, command_id),
    CONSTRAINT command_status_state_chk CHECK (state IN ('queued', 'running', 'succeeded', 'failed'))
);

-- Claim scan: queued commands, plus running commands whose consumer went silent.
CREATE INDEX command_status_state_idx
    ON command_status (topic, state, updated_at);

-- Retention sweep of terminal commands.
CREATE INDEX command_status_purge_idx
    ON command_status (updated_at)
    WHERE state IN ('succeeded', 'failed');

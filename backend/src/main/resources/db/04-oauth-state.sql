CREATE TABLE oauth_state (
    id              BIGINT PRIMARY KEY,
    provider        VARCHAR(64) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_oauth_state_created_at ON oauth_state(created_at);
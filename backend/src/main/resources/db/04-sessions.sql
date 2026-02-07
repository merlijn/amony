CREATE TABLE user_sessions (
    session_id      INT NOT NULL,
    user_id         VARCHAR(64) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_name     VARCHAR(128),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_active_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    invalidated_at  TIMESTAMPTZ,
    PRIMARY KEY (user_id, session_id)
);

CREATE INDEX idx_sessions_user_id ON user_sessions(user_id);
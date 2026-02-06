CREATE TABLE users (
    id               VARCHAR(64) PRIMARY KEY,
    auth_provider    VARCHAR(64) NOT NULL,
    auth_subject     VARCHAR(64) NOT NULL,
    email            VARCHAR(64) NOT NULL UNIQUE,
    time_registered  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    roles            VARCHAR(256)[] NOT NULL,
    CONSTRAINT unique_user_auth UNIQUE (auth_provider, auth_subject)
);

CREATE SEQUENCE user_id_seq MAXVALUE 2147483647;
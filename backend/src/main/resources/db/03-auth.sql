CREATE TABLE users (
    id               VARCHAR(64) PRIMARY KEY,
    provider         VARCHAR(64) NOT NULL,
    oauth_subject    VARCHAR(64) NOT NULL,
    email            VARCHAR(64) NOT NULL UNIQUE,
    time_registered  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

create TABLE registrations (
    id               SERIAL PRIMARY KEY,
    email            VARCHAR(64) NOT NULL UNIQUE,
    provider         VARCHAR(64) NOT NULL,
    oauth_subject    VARCHAR(64) NOT NULL,
    time_requested   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
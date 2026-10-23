CREATE TABLE buckets (
    bucket_id     VARCHAR(64) NOT NULL,
    bucket_type   VARCHAR(32) NOT NULL,
    required_role VARCHAR(64),
    settings      JSONB       NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT buckets_pk PRIMARY KEY (bucket_id)
);

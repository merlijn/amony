CREATE TABLE file_tracking (
    bucket_id              INT      NOT NULL,
    fs_size                BIGINT   NOT NULL,
    fs_path                VARCHAR,
    fs_last_modified_time  TIMESTAMPTZ,
    dedup_id               VARCHAR(64),
    first_seen_time        TIMESTAMPTZ NOT NULL,
    last_seen_time         TIMESTAMPTZ NOT NULL,

    CONSTRAINT file_tracking_pk PRIMARY KEY (bucket_id, fs_path, last_seen_time)
);

CREATE INDEX file_tracking_bucket_id_idx ON file_tracking (bucket_id);
CREATE INDEX file_tracking_path_idx      ON file_tracking (bucket_id, fs_path);
CREATE INDEX file_tracking_hash_idx      ON file_tracking (bucket_id, last_seen_time);

CREATE TABLE file_events (
    bucket_id              INT      NOT NULL,
    fs_path                VARCHAR,
    event_type             VARCHAR(64) NOT NULL,
    event_time             TIMESTAMPTZ NOT NULL,

    CONSTRAINT file_events_pk PRIMARY KEY (bucket_id, fs_path, event_time)
);

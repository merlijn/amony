CREATE TABLE resources (
    bucket_id              VARCHAR(64)   NOT NULL,
    resource_id            VARCHAR(64)   NOT NULL,
    user_id                VARCHAR(64)   NOT NULL,
    size                   BIGINT        NOT NULL,
    hash                   VARCHAR(64),
    content_type           VARCHAR(64),
    content_meta_tool_name VARCHAR(64),
    content_meta_tool_data VARCHAR,
    fs_path                VARCHAR,
    fs_creation_time       TIMESTAMPTZ,
    fs_last_modified_time  TIMESTAMPTZ,
    title                  VARCHAR(128),
    description            VARCHAR,
    thumbnail_timestamp    BIGINT,
    CONSTRAINT resources_pk PRIMARY KEY (bucket_id, resource_id)
);

CREATE INDEX resources_bucket_id_idx ON resources (bucket_id);

CREATE INDEX resources_hash_idx ON resources (resource_id);

CREATE TABLE tags (
    id         SERIAL NOT NULL,
    label      VARCHAR(64) NOT NULL,
    CONSTRAINT tags_pk PRIMARY KEY (id),
    CONSTRAINT label_unq UNIQUE (label)
);

CREATE INDEX tags_label_idx ON tags (label);

CREATE TABLE resource_tags (
    bucket_id   VARCHAR(64) NOT NULL,
    resource_id VARCHAR(64) NOT NULL,
    tag_id      INTEGER NOT NULL,
    CONSTRAINT resource_tags_pk
       PRIMARY KEY (bucket_id, resource_id, tag_id),
    CONSTRAINT resource_tags_tag_fk
       FOREIGN KEY (tag_id) REFERENCES tags (id),
   CONSTRAINT resource_tags_resource_fk
        FOREIGN KEY (bucket_id, resource_id)
        REFERENCES resources (bucket_id, resource_id)
);

CREATE INDEX resource_tags_by_id_idx
    ON resource_tags (bucket_id, resource_id);
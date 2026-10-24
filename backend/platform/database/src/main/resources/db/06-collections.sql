CREATE TABLE collections (
    id          UUID NOT NULL,
    parent_id   UUID,
    user_id     VARCHAR(64) NOT NULL,
    name        VARCHAR NOT NULL,
    description VARCHAR,
    CONSTRAINT collections_pk PRIMARY KEY (id),
    CONSTRAINT collections_parent_fk
        FOREIGN KEY (parent_id) REFERENCES collections (id) ON DELETE CASCADE
);

CREATE INDEX collections_parent_id_idx ON collections (parent_id);
CREATE INDEX collections_user_id_idx ON collections (user_id);

CREATE TABLE collection_tags (
    collection_id UUID NOT NULL,
    tag_id        INTEGER NOT NULL,
    CONSTRAINT collection_tags_pk PRIMARY KEY (collection_id, tag_id),
    CONSTRAINT collection_tags_collection_fk
        FOREIGN KEY (collection_id) REFERENCES collections (id) ON DELETE CASCADE,
    CONSTRAINT collection_tags_tag_fk
        FOREIGN KEY (tag_id) REFERENCES tags (id)
);

CREATE INDEX collection_tags_collection_id_idx ON collection_tags (collection_id);

CREATE TABLE collection_resources (
    collection_id UUID        NOT NULL,
    bucket_id     VARCHAR(64) NOT NULL,
    resource_id   VARCHAR(64) NOT NULL,
    CONSTRAINT collection_resources_pk PRIMARY KEY (collection_id, bucket_id, resource_id),
    CONSTRAINT collection_resources_collection_fk
        FOREIGN KEY (collection_id) REFERENCES collections (id) ON DELETE CASCADE,
    CONSTRAINT collection_resources_resource_fk
        FOREIGN KEY (bucket_id, resource_id)
        REFERENCES resources (bucket_id, resource_id) ON DELETE CASCADE
);

CREATE INDEX collection_resources_by_resource_idx
    ON collection_resources (bucket_id, resource_id);

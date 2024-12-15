create table "files"
(
    "bucket_id"           VARCHAR(128)  not null,
    "relative_path"       VARCHAR(1024) not null,
    "resource_id"         VARCHAR(128)  not null,
    "size"                BIGINT        not null,
    "content_type"        VARCHAR(128),
    "content_meta"        VARBINARY,
    "creation_time"       BIGINT,
    "last_modified_time"  BIGINT,
    "title"               VARCHAR(128),
    "description"         VARCHAR(1024),
    "thumbnail_timestamp" BIGINT,
    constraint "resources_pk" primary key ("bucket_id", "resource_id")
);

create index "bucket_id_idx" on "files" ("bucket_id");

create index "hash_idx" on "files" ("resource_id");

create table "resource_tags"
(
    "bucket_id"   VARCHAR(128) not null,
    "resource_id" VARCHAR(128) not null,
    "tag"         VARCHAR(128) not null,
    constraint "resource_tags_pk"
        primary key ("bucket_id", "resource_id", "tag")
);

create index "resource_idx"
    on "resource_tags" ("bucket_id", "resource_id");


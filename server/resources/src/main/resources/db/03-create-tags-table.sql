-- 1. Create the new tags table
create table "tags"
(
    "id"       IDENTITY not null,
    "label"    VARCHAR(64) not null,
    constraint "tags_pk" primary key ("id"),
    constraint "label_unq" unique ("label")
);

create index "tags_label_idx" on "tags" ("label");

-- 2. Create temporary table to store old relationships
CREATE TABLE "resource_tags_temp" (
  "bucket_id"   VARCHAR(128) not null,
  "resource_id" VARCHAR(128) not null,
  "tag"         VARCHAR(128) not null
);

INSERT INTO "resource_tags_temp"
SELECT * FROM "resource_tags";

-- 3. Drop the old table
DROP TABLE "resource_tags";

-- 4. Create the new resource_tags table with INTEGER tag_id
CREATE TABLE "resource_tags"
(
    "bucket_id"   VARCHAR(128) not null,
    "resource_id" VARCHAR(128) not null,
    "tag_id"      INTEGER not null,
    constraint "resource_tags_pk"
        primary key ("bucket_id", "resource_id", "tag_id"),
    constraint "resource_tags_tag_fk"
        foreign key ("tag_id") references "tags" ("id")
);

-- 5. Migrate unique tags to the new tags table
INSERT INTO "tags" ("label")
SELECT DISTINCT "tag"
FROM "resource_tags_temp";

-- 6. Insert old relationships with new tag IDs
INSERT INTO "resource_tags" ("bucket_id", "resource_id", "tag_id")
SELECT rt."bucket_id", rt."resource_id", t."id"
FROM "resource_tags_temp" rt
         JOIN "tags" t ON t."label" = rt."tag";

-- 7. Create the index on the new table
CREATE INDEX "resource_idx"
    ON "resource_tags" ("bucket_id", "resource_id");

-- 8. Drop the temporary table
DROP TABLE "resource_tags_temp";
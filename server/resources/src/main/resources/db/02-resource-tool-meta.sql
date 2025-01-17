alter table "files" drop column "content_meta";
alter table "files" add column "content_meta_tool_name" VARCHAR(32);
alter table "files" add column "content_meta_tool_data" VARCHAR;
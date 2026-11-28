create table staged_uploads
(
    bucket_id  varchar(64) not null,
    path       text        not null,
    created_at timestamptz not null default now(),
    primary key (bucket_id, path)
);

create index idx_staged_uploads_created_at on staged_uploads (bucket_id, created_at);

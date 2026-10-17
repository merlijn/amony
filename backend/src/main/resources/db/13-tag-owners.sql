ALTER TABLE tags ADD COLUMN owner VARCHAR(64) NOT NULL DEFAULT 'user';

UPDATE tags SET owner = 'user' WHERE owner IS NULL OR owner = '';

ALTER TABLE tags DROP CONSTRAINT IF EXISTS label_unq;

ALTER TABLE tags ADD CONSTRAINT tags_owner_label_unq UNIQUE (owner, label);

DROP INDEX IF EXISTS tags_label_idx;

CREATE INDEX IF NOT EXISTS tags_owner_label_idx ON tags (owner, label);

CREATE INDEX IF NOT EXISTS resource_tags_tag_id_idx ON resource_tags (tag_id);

CREATE INDEX IF NOT EXISTS collection_tags_tag_id_idx ON collection_tags (tag_id);

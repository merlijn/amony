-- Optional content hash over the whole file. Computed for uploads and on demand; populated later.
ALTER TABLE resources
    ADD COLUMN full_hash VARCHAR(128);

-- Speeds up exact-content lookups and duplicate queries once full hashes are populated.
CREATE INDEX IF NOT EXISTS resources_full_hash_idx
    ON resources (bucket_id, full_hash)
    WHERE full_hash IS NOT NULL;

-- Rows without a hash get a placeholder that the one-off migration replaces with a real hash.
UPDATE resources SET partial_hash = 'ph0_unknown' WHERE partial_hash IS NULL;

ALTER TABLE resources ALTER COLUMN partial_hash SET NOT NULL;

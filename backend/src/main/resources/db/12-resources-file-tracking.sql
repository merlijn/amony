-- Tracks filesystem identity for rename detection without rehashing
ALTER TABLE resources
    ADD COLUMN file_key VARCHAR(64);

-- Speeds up per-event lookups by partial hash during scans
CREATE INDEX IF NOT EXISTS resources_partial_hash_idx ON resources (bucket_id, partial_hash);

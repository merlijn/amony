-- Optional content hash over the whole file. Computed for uploads and on demand; populated later.
ALTER TABLE resources
    ADD COLUMN full_hash VARCHAR(128);

-- Rows without a hash get a placeholder that the one-off migration replaces with a real hash.
UPDATE resources SET partial_hash = 'ph0_unknown' WHERE partial_hash IS NULL;

ALTER TABLE resources ALTER COLUMN partial_hash SET NOT NULL;

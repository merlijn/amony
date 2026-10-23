-- 1. Rename fs_last_modified_time to time_last_modified
ALTER TABLE resources
    RENAME COLUMN fs_last_modified_time TO time_last_modified;

-- 2. Rename fs_creation_time to time_added, add NOT NULL constraint with default to epoch (0)
ALTER TABLE resources
    RENAME COLUMN fs_creation_time TO time_added;

-- Ensure time_added is NOT NULL, backfilling with default where NULL
UPDATE resources
SET time_added = '1970-01-01 00:00:00 UTC'::timestamptz
WHERE time_added IS NULL;

ALTER TABLE resources
    ALTER COLUMN time_added SET NOT NULL;

-- 3. Add new column time_created (TIMESTAMPTZ, optional, so NULL allowed)
ALTER TABLE resources
    ADD COLUMN time_created TIMESTAMPTZ;
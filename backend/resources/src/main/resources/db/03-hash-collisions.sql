-- Drop the redundant index
DROP INDEX resources_hash_idx;

-- Create a proper hash index
CREATE INDEX resources_hash_idx ON resources (hash) WHERE hash IS NOT NULL;

CREATE MATERIALIZED VIEW hash_collisions AS
SELECT
    hash,
    COUNT(*) as resource_count,
    array_agg(DISTINCT bucket_id) as buckets,
    array_agg((bucket_id, resource_id)::text ORDER BY bucket_id, resource_id) as resources
FROM resources
WHERE hash IS NOT NULL
GROUP BY hash
HAVING COUNT(*) > 1
ORDER BY resource_count DESC, hash;

CREATE INDEX ON hash_collisions (hash);
CREATE INDEX ON hash_collisions (resource_count DESC);
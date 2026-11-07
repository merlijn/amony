# Message Bus Performance Improvements

## Baseline Performance

Using `MessageBusSpec` on an Apple M2 MacBook Pro against a Testcontainers PostgreSQL 17.2 instance (16 partitions, 1
producer fiber, 1 consumer fiber), baseline throughput is roughly **440–460 msg/s** for both producing and consuming
(~2.2 ms per message).

At this level, PostgreSQL CPU and disk are largely idle. The primary bottleneck is **sequential TCP round-trips**
between the JVM and the PostgreSQL container (~0.4–0.5 ms per round-trip over the Docker VM bridge):

- **Producer (`topic.publish(msg)`):** 4–5 sequential round-trips per message (`BEGIN`, `PARSE/EXECUTE INSERT`,
  `NOTIFY`, `COMMIT`).
- **Consumer (`drain`):** 4 sequential round-trips per message (`PREPARE/EXECUTE claimNextUnprocessed`,
  `PREPARE/EXECUTE commitOffset`).

## Levers for Higher Throughput

If higher throughput is ever required, the following adjustments provide the highest leverage (ordered by impact):

### 1. Batch Claiming Within a Partition (5x–10x Consumer Speedup)

Currently, `claimNextUnprocessed` leases a partition, fetches `LIMIT 1` message from `message_log`, runs the processor,
and commits `last_offset` per message.

Once a consumer holds the lease on partition `P`, it already has exclusive access to that partition:

- Fetch a batch of messages (`LIMIT N`, e.g., 50 or 100) for the leased partition in a single query.
- Process them sequentially in memory to preserve strict per-key ordering.
- Commit `last_offset` once at the end of the batch (or periodically within the batch).

This reduces database round-trips from 4 per message to `4 / N` per message.

### 2. Concurrent Partition Workers per Node (Linear Multi-Core Scaling)

Currently, `processAtLeastOnce` uses a single `Mutex[IO]` per stream, draining one partition at a time on a given node.

Because partitions are leased independently in the database via `FOR UPDATE OF mc2 SKIP LOCKED`, a single node can run
`W` concurrent drain fibers (up to the number of partitions and sized appropriately for the database connection pool) to
process multiple partitions in parallel.

### 3. Batch Publishing and Coalesced `NOTIFY` (5x–20x Producer Speedup for Bulk Operations)

When publishing multiple messages inside a single transaction (such as bulk tag updates in `LocalDirectoryBucket`),
calling `topic.publish(session, event)` in a loop performs `N` individual `INSERT` statements and sends `N` identical
`NOTIFY` commands over the wire.

Adding a `publishAll(session: Session[IO], messages: NonEmptyList[E]): IO[Unit]` method that performs a single multi-row
`INSERT INTO message_log VALUES ...` followed by a single `NOTIFY` reduces `2N` round-trips to 2.

### 4. Session Reuse During `drain`

Currently, each `MessageLog` operation checks out a session from the pool (`pool.use(...)`) for a single statement.
Holding a single session across a `drain` cycle avoids repeated pool checkout and statement preparation overhead.

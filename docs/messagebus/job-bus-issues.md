# Job Bus Follow-up Issues

Known reliability and ordering issues in `JobBus` (`JobBusImpl`, `JobStore`) to address in a follow-up PR.

## 1. Unfenced `JobStore` mutations and transactional DB writes

`MessageLog` fences `renewLease`, `commitOffset`, and `recordFailure` with `lease_generation`, but that fencing token is
not propagated to `JobBus` or `JobStore`.

If Worker A loses its partition lease while running job `J1` and Worker B takes over `J1` (and potentially advances to
`J2` for the same key):

- `JobStoreQueries.succeed`, `fail`, and `requeue` only filter by `WHERE topic = $1 AND job_id = $2`. A stale Worker A
  can overwrite Worker B's `succeeded` status back to `queued` or `failed`.
- In `JobBusImpl.processAtLeastOnce((session, payload) => ...)`, the handler's domain writes and `store.succeed` commit
  without checking the partition lease, so `J1`'s DB writes can commit after `J2`'s.

**Fix:**

- Fence `JobStore` mutations (`succeed`, `fail`, `requeue`) by `attempts` (returned from `claim`) and
  `state = 'running'`.
- For transactional handlers, commit the consumer offset (or verify `message_consumer` with `lease_generation` and
  `locked_until > clock_timestamp()`) in the same transaction as the handler's writes and `store.succeed`, rolling back
  if 0 rows match.

## 2. `Left(error)` does not roll back the Skunk transaction

In `JobBusImpl.processAtLeastOnce((session, payload) => ...)`:

```scala
session.transaction.use { _ =>
  handler(session, job.payload).flatMap {
    case Left(error) => IO.pure(Left(error))
    case Right(())   => store.succeed(session, key.name, job.id).as(Right(()))
  }
}
```

Returning `Left(error)` completes the `use` block normally, so Skunk commits the transaction. Any partial writes the
handler performed before returning `Left(error)` are committed even though the job is marked `failed`.

**Fix:** Call `tx.rollback` explicitly when the handler returns `Left(error)`.

## 3. Startup consumer registration is not retried

`JobBusImpl.processJobs` runs `Stream.eval(log.ensureConsumer(key.name, key.name, key.partitions, 0))` outside
`MessageTopicImpl`'s `restartOnError`. A transient DB outage at startup will terminate the job consumer fiber
immediately instead of retrying.

**Fix:** Wrap `ensureConsumer` with retry/backoff (matching `MessageTopicImpl.register`).

## 4. Deserialization failures bypass `maxAttempts`

`key.decode(row.payload)` runs in `MessageTopicImpl.process` before `JobBusImpl`'s processor is invoked. A malformed
payload on a job topic never reaches `JobBusImpl`'s `maxAttempts` handling and blocks the partition indefinitely.

**Fix:** Either handle decode failures in `MessageTopicImpl` (see [Poisoned Messages](poisoned-messages.md)) or surface
decode errors to the consumer wrapper.

## 5. Notes for `LISTEN`/`NOTIFY` job status (`realtime-job-status.md`)

Captured in [Realtime Job Status](realtime-job-status.md):

- Register subscribers before reading the initial `job_status` row to avoid missing a terminal notification.
- Only query `job_status` on `NOTIFY` when the local hub has an active subscriber for that `job_id`.
- Multiplex all `LISTEN` channels onto one shared session per node so adding a job-status listener does not add further
  pressure to the 10-connection pool in `database.conf`.

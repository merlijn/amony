# Addressing Poisoned Messages

By design, `MessageTopic.processAtLeastOnce` retries a failing message in place on its partition rather than skipping
it. That preserves strict per-partition ordering, but a single poisoned message (a bug, bad data, or a corrupt payload)
blocks every other key hashed to the same partition.

`JobBus` already caps retries with `maxAttempts` and skips undecodable job messages (marking identifiable jobs as
`Failed`), so this primarily applies to raw `MessageTopic` consumers.

If we revisit the block-by-design trade-off, here are the main options in increasing order of complexity.

## 1. Keep blocking, add alerting and an operator escape hatch

Lowest complexity if poisoned messages are rare:

- Alert on `message_consumer.attempts` exceeding a threshold or on consumer lag (`last_allocated_offset - last_offset`).
- Provide an admin command or SQL procedure to copy a stuck row into a dead-letter table and advance `last_offset` by 1
  so operators do not hand-edit `message_consumer` during an incident.
- Optionally route `key.decode` failures straight to the dead-letter table, since retrying corrupt bytes cannot succeed
  without a deployment.

## 2. Dead-letter table (`message_dlq`) with `maxAttempts` and exponential backoff

`message_consumer` already tracks `attempts` and `last_error`.

- Once `attempts >= maxAttempts` (or on a classified non-retryable error such as a decode failure), insert the row
  (`topic`, `consumer_id`, `partition`, `partition_offset`, `message_key`, `payload`, `last_error`, `attempts`) into
  `message_dlq` and advance `last_offset` in the same transaction.
- **Prerequisite — exponential backoff:** `MessageBusConfig.retryBackoff` is currently a flat 3 seconds. Before adding
  `maxAttempts`, compute backoff from `attempts` (for example `3s * 2^attempts`, capped at a few minutes) so a brief
  outage like a Solr restart does not prematurely dead-letter healthy messages.
- **Trade-off:** Unblocks the partition, but breaks per-key ordering for the failed key if later messages for that same
  key are processed while the earlier one sits in `message_dlq`. For idempotent full-state consumers (such as
  `solr-indexer`), this is usually acceptable.

## 3. Per-key parking / quarantine

Eliminates collateral damage to unrelated keys on the same partition while preserving strict per-key ordering:

- When a message for `message_key = K` exhausts retries, move it to a
  `parked_messages(topic, consumer_id, message_key, partition_offset, payload, last_error, ...)` table and advance
  `last_offset` in the same transaction.
- Before invoking the processor for any subsequent message on that partition, check whether its `message_key` has a
  parked entry. If so, append the new message to `parked_messages` and advance `last_offset` without running the
  processor. Unrelated keys on the same partition continue draining normally.
- Once the underlying bug or data issue is fixed, an operator (or background retry job) drains `parked_messages` for key
  `K` in `partition_offset` order.
- **Trade-off:** More moving parts and an extra lookup per message (which can be gated on a fast check when no keys are
  parked for the partition), in exchange for both strict per-key ordering and isolation from head-of-line blocking.

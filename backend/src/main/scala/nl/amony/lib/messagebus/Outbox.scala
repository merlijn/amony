package nl.amony.lib.messagebus

import java.time.{Duration, OffsetDateTime}

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import io.circe.Json
import skunk.*
import skunk.circe.codec.all.jsonb
import skunk.codec.all.*
import skunk.implicits.*

enum OutboxStatus:
  case Pending, Claimed, Processed, Failed

object OutboxStatus:

  val codec: Codec[OutboxStatus] = varchar(16).imap {
    case "pending"   => OutboxStatus.Pending
    case "claimed"   => OutboxStatus.Claimed
    case "processed" => OutboxStatus.Processed
    case "failed"    => OutboxStatus.Failed
    case other       => throw new IllegalArgumentException(s"Unknown outbox status: $other")
  }(_.toString.toLowerCase)

final case class OutboxRow(
  id: Long,
  topic: String,
  payload: Json,
  status: OutboxStatus,
  attempts: Int,
  lastError: Option[String],
  createdAt: OffsetDateTime,
  claimedAt: Option[OffsetDateTime],
  processedAt: Option[OffsetDateTime]
)

object OutboxRow:

  val codec: Codec[OutboxRow] =
    (int8 *: varchar(128) *: jsonb *: OutboxStatus.codec *: int4 *: text.opt *: timestamptz *: timestamptz.opt *: timestamptz.opt).to[OutboxRow]

private[messagebus] object OutboxQueries:

  val insert: Query[(String, Json), Long] =
    sql"insert into event_outbox (topic, payload) values ($varchar, $jsonb) returning id".query(int8)

  // Claim a batch of pending events, plus any events left claimed for longer than the TTL (a crashed consumer).
  // FOR UPDATE SKIP LOCKED lets competing consumers claim disjoint batches without blocking on each other.
  val claim: Query[(String, Duration, Int), OutboxRow] =
    sql"""
      update event_outbox
      set status = 'claimed', claimed_at = now(), attempts = attempts + 1
      where id in (
        select id
        from event_outbox
        where topic = $varchar
          and (status = 'pending' or (status = 'claimed' and claimed_at < now() - $interval))
        order by created_at, id
        limit $int4
        for update skip locked
      )
      returning id, topic, payload, status, attempts, last_error, created_at, claimed_at, processed_at
    """.query(OutboxRow.codec)

  val markProcessed: Command[Long] =
    sql"update event_outbox set status = 'processed', processed_at = now(), last_error = null where id = $int8".command

  val markFailed: Command[(String, Long)] =
    sql"update event_outbox set status = 'failed', processed_at = now(), last_error = $text where id = $int8".command

  // Return an event to the pending state so it can be claimed again, without losing the recorded error.
  val releaseForRetry: Command[(String, Long)] =
    sql"update event_outbox set status = 'pending', claimed_at = null, last_error = $text where id = $int8".command

  val purge: Command[Duration] =
    sql"delete from event_outbox where status in ('processed', 'failed') and processed_at < now() - $interval".command

/** Persistence for the transactional outbox that backs the event bus. */
final private[messagebus] class EventOutbox(pool: Resource[IO, Session[IO]]):

  private val chunkSize = 64

  def enqueue(session: Session[IO], topic: String, payload: Json): IO[Unit] =
    session.prepare(OutboxQueries.insert).flatMap(_.unique((topic, payload))).void

  def claim(topic: String, ttl: Duration, batchSize: Int): IO[List[OutboxRow]] =
    pool.use(session => session.prepare(OutboxQueries.claim).flatMap(_.stream((topic, ttl, batchSize), chunkSize).compile.toList))

  def markProcessed(id: Long): IO[Unit] =
    pool.use(session => session.prepare(OutboxQueries.markProcessed).flatMap(_.execute(id)).void)

  def markFailed(id: Long, error: String): IO[Unit] =
    pool.use(session => session.prepare(OutboxQueries.markFailed).flatMap(_.execute((error, id))).void)

  def releaseForRetry(id: Long, error: String): IO[Unit] =
    pool.use(session => session.prepare(OutboxQueries.releaseForRetry).flatMap(_.execute((error, id))).void)

  def purge(olderThan: Duration): IO[Unit] =
    pool.use(session => session.prepare(OutboxQueries.purge).flatMap(_.execute(olderThan)).void)

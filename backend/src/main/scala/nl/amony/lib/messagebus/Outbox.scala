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

  // Claim the single oldest pending event for a topic, so the consumer processes events in insertion order.
  // FOR UPDATE SKIP LOCKED lets a competing consumer claim the next one without blocking.
  val claimOldest: Query[String, OutboxRow] =
    sql"""
      update event_outbox
      set status = 'claimed', claimed_at = now(), attempts = attempts + 1
      where id = (
        select id
        from event_outbox
        where topic = $varchar and status = 'pending'
        order by id
        limit 1
        for update skip locked
      )
      returning id, topic, payload, status, attempts, last_error, created_at, claimed_at, processed_at
    """.query(OutboxRow.codec)

  // Return events left claimed by a consumer that stopped, so they are processed again in order.
  val resetClaimed: Command[String] =
    sql"update event_outbox set status = 'pending', claimed_at = null where topic = $varchar and status = 'claimed'".command

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

  def enqueue(session: Session[IO], topic: String, payload: Json): IO[Unit] =
    session.prepare(OutboxQueries.insert).flatMap(_.unique((topic, payload))).void

  def claimOldest(topic: String): IO[Option[OutboxRow]] =
    pool.use(session => session.prepare(OutboxQueries.claimOldest).flatMap(_.option(topic)))

  def resetClaimed(topic: String): IO[Unit] =
    pool.use(session => session.prepare(OutboxQueries.resetClaimed).flatMap(_.execute(topic)).void)

  def markProcessed(id: Long): IO[Unit] =
    pool.use(session => session.prepare(OutboxQueries.markProcessed).flatMap(_.execute(id)).void)

  def markFailed(id: Long, error: String): IO[Unit] =
    pool.use(session => session.prepare(OutboxQueries.markFailed).flatMap(_.execute((error, id))).void)

  def releaseForRetry(id: Long, error: String): IO[Unit] =
    pool.use(session => session.prepare(OutboxQueries.releaseForRetry).flatMap(_.execute((error, id))).void)

  def purge(olderThan: Duration): IO[Unit] =
    pool.use(session => session.prepare(OutboxQueries.purge).flatMap(_.execute(olderThan)).void)

package nl.amony.lib.messagebus

import java.time.{Duration, OffsetDateTime}

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import io.circe.Json
import skunk.*
import skunk.circe.codec.all.jsonb
import skunk.codec.all.*
import skunk.implicits.*

enum MessageQueueStatus:
  case Pending, Claimed, Processed, Failed

object MessageQueueStatus:

  val codec: Codec[MessageQueueStatus] = varchar(16).imap {
    case "pending"   => MessageQueueStatus.Pending
    case "claimed"   => MessageQueueStatus.Claimed
    case "processed" => MessageQueueStatus.Processed
    case "failed"    => MessageQueueStatus.Failed
    case other       => throw new IllegalArgumentException(s"Unknown message queue status: $other")
  }(_.toString.toLowerCase)

final case class MessageQueueRow(
  id: Long,
  topic: String,
  payload: Json,
  status: MessageQueueStatus,
  attempts: Int,
  lastError: Option[String],
  createdAt: OffsetDateTime,
  claimedAt: Option[OffsetDateTime],
  processedAt: Option[OffsetDateTime]
)

object MessageQueueRow:

  val codec: Codec[MessageQueueRow] =
    (int8 *: varchar(
      128
    ) *: jsonb *: MessageQueueStatus.codec *: int4 *: text.opt *: timestamptz *: timestamptz.opt *: timestamptz.opt).to[MessageQueueRow]

private[messagebus] object MessageQueueQueries:

  val insert: Query[(String, Json), Long] =
    sql"insert into message_queue (topic, payload) values ($varchar, $jsonb) returning id".query(int8)

  // Claim the single oldest pending message for a topic, so the consumer processes messages in insertion order.
  // FOR UPDATE SKIP LOCKED lets a competing consumer claim the next one without blocking.
  val claimOldest: Query[String, MessageQueueRow] =
    sql"""
      update message_queue
      set status = 'claimed', claimed_at = now(), attempts = attempts + 1
      where id = (
        select id
        from message_queue
        where topic = $varchar and status = 'pending'
        order by id
        limit 1
        for update skip locked
      )
      returning id, topic, payload, status, attempts, last_error, created_at, claimed_at, processed_at
    """.query(MessageQueueRow.codec)

  // Return messages left claimed by a consumer that stopped, so they are processed again in order.
  val resetClaimed: Command[String] =
    sql"update message_queue set status = 'pending', claimed_at = null where topic = $varchar and status = 'claimed'".command

  val markProcessed: Command[Long] =
    sql"update message_queue set status = 'processed', processed_at = now(), last_error = null where id = $int8".command

  val markFailed: Command[(String, Long)] =
    sql"update message_queue set status = 'failed', processed_at = now(), last_error = $text where id = $int8".command

  // Return a message to the pending state so it can be claimed again, without losing the recorded error.
  val releaseForRetry: Command[(String, Long)] =
    sql"update message_queue set status = 'pending', claimed_at = null, last_error = $text where id = $int8".command

  val purge: Command[Duration] =
    sql"delete from message_queue where status in ('processed', 'failed') and processed_at < now() - $interval".command

/** Persistence for the durable message queue that backs the message bus. */
final private[messagebus] class MessageQueue(pool: Resource[IO, Session[IO]]):

  def enqueue(session: Session[IO], topic: String, payload: Json): IO[Unit] =
    session.prepare(MessageQueueQueries.insert).flatMap(_.unique((topic, payload))).void

  def claimOldest(topic: String): IO[Option[MessageQueueRow]] =
    pool.use(session => session.prepare(MessageQueueQueries.claimOldest).flatMap(_.option(topic)))

  def resetClaimed(topic: String): IO[Unit] =
    pool.use(session => session.prepare(MessageQueueQueries.resetClaimed).flatMap(_.execute(topic)).void)

  def markProcessed(id: Long): IO[Unit] =
    pool.use(session => session.prepare(MessageQueueQueries.markProcessed).flatMap(_.execute(id)).void)

  def markFailed(id: Long, error: String): IO[Unit] =
    pool.use(session => session.prepare(MessageQueueQueries.markFailed).flatMap(_.execute((error, id))).void)

  def releaseForRetry(id: Long, error: String): IO[Unit] =
    pool.use(session => session.prepare(MessageQueueQueries.releaseForRetry).flatMap(_.execute((error, id))).void)

  def purge(olderThan: Duration): IO[Unit] =
    pool.use(session => session.prepare(MessageQueueQueries.purge).flatMap(_.execute(olderThan)).void)

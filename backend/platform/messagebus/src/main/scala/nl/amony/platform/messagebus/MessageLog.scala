package nl.amony.platform.messagebus

import java.time.{Duration, OffsetDateTime}

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import io.circe.Json
import skunk.*
import skunk.circe.codec.all.jsonb
import skunk.codec.all.*
import skunk.implicits.*

/** A message in the durable, append-only log that backs the message bus. */
final case class MessageLogRow(
  id: Long,
  topic: String,
  partition: Int,
  messageKey: String,
  payload: Json,
  createdAt: OffsetDateTime
)

object MessageLogRow:

  val codec: Codec[MessageLogRow] =
    (int8 *: varchar(128) *: int4 *: varchar(256) *: jsonb *: timestamptz).to[MessageLogRow]

private[messagebus] object MessageLogQueries:

  val insert: Query[(String, Int, String, Json), Long] =
    sql"insert into message_log (topic, partition, message_key, payload) values ($varchar, $int4, $varchar, $jsonb) returning id".query(int8)

  val latestId: Query[String, Long] =
    sql"select coalesce(max(id), 0) from message_log where topic = $varchar".query(int8)

  val ensureConsumerPartition: Command[(String, String, Int, Long)] =
    sql"insert into message_consumer (topic, consumer_id, partition, last_offset) values ($varchar, $varchar, $int4, $int8) on conflict (topic, consumer_id, partition) do nothing".command

  /**
   * Claims the next unprocessed message from any unlocked partition of `(topic, consumer_id)` by setting a temporary
   * lease (`locked_until`). Only messages whose producing transaction is below `pg_snapshot_xmin` are visible, so an
   * out-of-order commit with a higher sequence ID never causes an earlier in-flight transaction's message to be
   * skipped.
   */
  val claimNextUnprocessed: Query[(Duration, String, String), MessageLogRow] =
    sql"""
      update message_consumer mc
      set locked_until = now() + $interval,
          updated_at   = now()
      from (
        select mc2.topic, mc2.consumer_id, mc2.partition, mq.id, mq.message_key, mq.payload, mq.created_at
        from message_consumer mc2
        cross join lateral (
          select id, message_key, payload, created_at
          from message_log mq
          where mq.topic = mc2.topic
            and mq.partition = mc2.partition
            and mq.id > mc2.last_offset
            and mq.tx_id < pg_snapshot_xmin(pg_current_snapshot())
          order by mq.id
          limit 1
        ) mq
        where mc2.topic = $varchar
          and mc2.consumer_id = $varchar
          and (mc2.locked_until is null or mc2.locked_until <= now())
        order by mq.id
        for update of mc2 skip locked
        limit 1
      ) claimed
      where mc.topic = claimed.topic
        and mc.consumer_id = claimed.consumer_id
        and mc.partition = claimed.partition
      returning claimed.id, mc.topic, mc.partition, claimed.message_key, claimed.payload, claimed.created_at
    """.query(MessageLogRow.codec)

  val renewLease: Command[(Duration, String, String, Int)] =
    sql"""
      update message_consumer
      set locked_until = now() + $interval, updated_at = now()
      where topic = $varchar and consumer_id = $varchar and partition = $int4
    """.command

  val commitOffset: Command[(Long, String, String, Int, Long)] =
    sql"""
      update message_consumer
      set last_offset = $int8, locked_until = null, attempts = 0, last_error = null, updated_at = now()
      where topic = $varchar and consumer_id = $varchar and partition = $int4 and last_offset < $int8
    """.command

  val recordFailure: Command[(String, Duration, String, String, Int)] =
    sql"""
      update message_consumer
      set attempts = attempts + 1, last_error = $text, locked_until = now() + $interval, updated_at = now()
      where topic = $varchar and consumer_id = $varchar and partition = $int4
    """.command

  // Delete only messages every consumer group has already processed on that partition, so nothing unread is ever lost.
  val purge: Command[(String, Duration)] =
    sql"""
      delete from message_log mq
      where mq.topic = $varchar
        and mq.id <= (
          select min(mc.last_offset)
          from message_consumer mc
          where mc.topic = mq.topic and mc.partition = mq.partition
        )
        and mq.created_at < now() - $interval
    """.command

/** Persistence for the durable log and the per-consumer-partition offsets that back the message bus. */
final private[messagebus] class MessageLog(pool: Resource[IO, Session[IO]]):

  def enqueue(session: Session[IO], topic: String, partition: Int, messageKey: String, payload: Json): IO[Unit] =
    session.prepare(MessageLogQueries.insert).flatMap(_.unique((topic, partition, messageKey, payload))).void

  def latestId(topic: String): IO[Long] =
    pool.use(session => session.prepare(MessageLogQueries.latestId).flatMap(_.unique(topic)))

  def ensureConsumer(topic: String, consumerId: String, partitions: Int, initialOffset: Long): IO[Unit] =
    pool.use { session =>
      session.prepare(MessageLogQueries.ensureConsumerPartition).flatMap { cmd =>
        (0 until partitions).toList.traverse_(p => cmd.execute((topic, consumerId, p, initialOffset)))
      }
    }

  def claimNextUnprocessed(topic: String, consumerId: String, leaseDuration: Duration): IO[Option[MessageLogRow]] =
    pool.use(session => session.prepare(MessageLogQueries.claimNextUnprocessed).flatMap(_.option((leaseDuration, topic, consumerId))))

  def renewLease(topic: String, consumerId: String, partition: Int, leaseDuration: Duration): IO[Unit] =
    pool.use(session => session.prepare(MessageLogQueries.renewLease).flatMap(_.execute((leaseDuration, topic, consumerId, partition))).void)

  def commitOffset(topic: String, consumerId: String, partition: Int, offset: Long): IO[Unit] =
    pool.use(session => session.prepare(MessageLogQueries.commitOffset).flatMap(_.execute((offset, topic, consumerId, partition, offset))).void)

  def recordFailure(topic: String, consumerId: String, partition: Int, error: String, retryBackoff: Duration): IO[Unit] =
    pool.use(session =>
      session.prepare(MessageLogQueries.recordFailure).flatMap(_.execute((error, retryBackoff, topic, consumerId, partition))).void
    )

  def purge(topic: String, olderThan: Duration): IO[Unit] =
    pool.use(session => session.prepare(MessageLogQueries.purge).flatMap(_.execute((topic, olderThan))).void)

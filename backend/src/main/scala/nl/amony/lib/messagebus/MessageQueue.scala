package nl.amony.lib.messagebus

import java.time.{Duration, OffsetDateTime}

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import io.circe.Json
import skunk.*
import skunk.circe.codec.all.jsonb
import skunk.codec.all.*
import skunk.implicits.*

/** A message in the durable, append-only log that backs the message bus. */
final case class MessageQueueRow(id: Long, topic: String, payload: Json, createdAt: OffsetDateTime)

object MessageQueueRow:

  val codec: Codec[MessageQueueRow] =
    (int8 *: varchar(128) *: jsonb *: timestamptz).to[MessageQueueRow]

private[messagebus] object MessageQueueQueries:

  val insert: Query[(String, Json), Long] =
    sql"insert into message_queue (topic, payload) values ($varchar, $jsonb) returning id".query(int8)

  val latestId: Query[String, Long] =
    sql"select coalesce(max(id), 0) from message_queue where topic = $varchar".query(int8)

  // The next message after a consumer group's committed offset, so each group reads the log independently.
  val nextUnprocessed: Query[(String, String), MessageQueueRow] =
    sql"""
      select mq.id, mq.topic, mq.payload, mq.created_at
      from message_consumer mc
      join message_queue mq on mq.topic = mc.topic and mq.id > mc.last_offset
      where mc.topic = $varchar and mc.consumer_id = $varchar
      order by mq.id
      limit 1
    """.query(MessageQueueRow.codec)

  val ensureConsumer: Command[(String, String, Long)] =
    sql"insert into message_consumer (topic, consumer_id, last_offset) values ($varchar, $varchar, $int8) on conflict (topic, consumer_id) do nothing".command

  val commitOffset: Command[(Long, String, String)] =
    sql"""
      update message_consumer
      set last_offset = $int8, attempts = 0, last_error = null, updated_at = now()
      where topic = $varchar and consumer_id = $varchar
    """.command

  val recordFailure: Command[(String, String, String)] =
    sql"""
      update message_consumer
      set attempts = attempts + 1, last_error = $text, updated_at = now()
      where topic = $varchar and consumer_id = $varchar
    """.command

  // Delete only messages every consumer group has already processed, so nothing unread is ever lost.
  val purge: Command[(String, Duration)] =
    sql"""
      delete from message_queue mq
      where mq.topic = $varchar
        and mq.id <= (select min(last_offset) from message_consumer mc where mc.topic = mq.topic)
        and mq.created_at < now() - $interval
    """.command

/** Persistence for the durable log and the per-consumer offsets that back the message bus. */
final private[messagebus] class MessageQueue(pool: Resource[IO, Session[IO]]):

  def enqueue(session: Session[IO], topic: String, payload: Json): IO[Unit] =
    session.prepare(MessageQueueQueries.insert).flatMap(_.unique((topic, payload))).void

  def latestId(topic: String): IO[Long] =
    pool.use(session => session.prepare(MessageQueueQueries.latestId).flatMap(_.unique(topic)))

  def ensureConsumer(topic: String, consumerId: String, initialOffset: Long): IO[Unit] =
    pool.use(session => session.prepare(MessageQueueQueries.ensureConsumer).flatMap(_.execute((topic, consumerId, initialOffset))).void)

  def nextUnprocessed(topic: String, consumerId: String): IO[Option[MessageQueueRow]] =
    pool.use(session => session.prepare(MessageQueueQueries.nextUnprocessed).flatMap(_.option((topic, consumerId))))

  def commitOffset(topic: String, consumerId: String, offset: Long): IO[Unit] =
    pool.use(session => session.prepare(MessageQueueQueries.commitOffset).flatMap(_.execute((offset, topic, consumerId))).void)

  def recordFailure(topic: String, consumerId: String, error: String): IO[Unit] =
    pool.use(session => session.prepare(MessageQueueQueries.recordFailure).flatMap(_.execute((error, topic, consumerId))).void)

  def purge(topic: String, olderThan: Duration): IO[Unit] =
    pool.use(session => session.prepare(MessageQueueQueries.purge).flatMap(_.execute((topic, olderThan))).void)

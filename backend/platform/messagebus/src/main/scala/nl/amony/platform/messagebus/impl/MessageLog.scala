package nl.amony.platform.messagebus.impl

import java.time.{Duration, OffsetDateTime}

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import skunk.*
import skunk.codec.all.*
import skunk.data.Completion
import skunk.implicits.*

/** A message in the durable, append-only log that backs the message bus. */
final case class MessageLogRow(
  id: Long,
  topic: String,
  partition: Int,
  messageKey: String,
  payload: Array[Byte],
  createdAt: OffsetDateTime,
  offset: Long,
  leaseGeneration: Long
)

object MessageLogRow:

  val codec: Codec[MessageLogRow] =
    (int8 *: varchar(128) *: int4 *: varchar(256) *: bytea *: timestamptz *: int8 *: int8).to[MessageLogRow]

private[messagebus] object MessageLogQueries:

  val insert: Command[(String, Int, String, Array[Byte])] =
    sql"""
      with allocated as (
        insert into message_partition (topic, partition, last_allocated_offset)
        values ($varchar, $int4, 1)
        on conflict (topic, partition) do update
        set last_allocated_offset = message_partition.last_allocated_offset + 1
        returning topic, partition, last_allocated_offset
      )
      insert into message_log (topic, partition, partition_offset, message_key, payload)
      select topic, partition, last_allocated_offset, $varchar, $bytea from allocated
    """.command

  private val batchRowCodec: Encoder[(Int, Long, String, Array[Byte])] =
    int4 *: int8 *: varchar *: bytea

  def insertBatch(size: Int): Command[(List[(Int, Long, String, Array[Byte])], String)] =
    sql"""
      with input(partition, seq, message_key, payload) as (
        values ${batchRowCodec.values.list(size)}
      ),
      counts as (
        select partition, count(*)::bigint as cnt
        from input
        group by partition
        order by partition
      ),
      allocated as (
        insert into message_partition (topic, partition, last_allocated_offset)
        select $varchar, partition, cnt
        from counts
        order by partition
        on conflict (topic, partition) do update
        set last_allocated_offset = message_partition.last_allocated_offset + excluded.last_allocated_offset
        returning topic, partition, last_allocated_offset
      )
      insert into message_log (topic, partition, partition_offset, message_key, payload)
      select a.topic, a.partition, a.last_allocated_offset - c.cnt + i.seq, i.message_key, i.payload
      from input i
      join counts c on c.partition = i.partition
      join allocated a on a.partition = i.partition
      order by a.partition, i.seq
    """.command

  val ensureConsumer: Command[(String, String, Int, Option[Long])] =
    sql"""
      with settings as (
        select $varchar as topic, $varchar as consumer_id, $int4 as partitions, ${int8.opt} as initial_offset
      )
      insert into message_consumer (topic, consumer_id, partition, last_offset)
      select s.topic, s.consumer_id, p.partition, coalesce(s.initial_offset, mp.last_allocated_offset, 0)
      from settings s
      cross join lateral generate_series(0, s.partitions - 1) p(partition)
      left join message_partition mp on mp.topic = s.topic and mp.partition = p.partition
      order by p.partition
      on conflict (topic, consumer_id, partition) do nothing
    """.command

  val claimNextUnprocessed: Query[(Duration, String, String), MessageLogRow] =
    sql"""
      update message_consumer mc
      set locked_until     = clock_timestamp() + $interval,
          lease_generation = mc.lease_generation + 1,
          updated_at       = clock_timestamp()
      from (
        select mc2.topic, mc2.consumer_id, mc2.partition, mq.id, mq.message_key, mq.payload, mq.created_at, mq.partition_offset
        from message_consumer mc2
        cross join lateral (
          select id, message_key, payload, created_at, partition_offset
          from message_log mq
          where mq.topic = mc2.topic
            and mq.partition = mc2.partition
            and mq.partition_offset > mc2.last_offset
          order by mq.partition_offset
          limit 1
        ) mq
        where mc2.topic = $varchar
          and mc2.consumer_id = $varchar
          and (mc2.locked_until is null or mc2.locked_until <= clock_timestamp())
        order by mq.id
        for update of mc2 skip locked
        limit 1
      ) claimed
      where mc.topic = claimed.topic
        and mc.consumer_id = claimed.consumer_id
        and mc.partition = claimed.partition
        and mc.last_offset < claimed.partition_offset
        and (mc.locked_until is null or mc.locked_until <= clock_timestamp())
      returning claimed.id, mc.topic, mc.partition, claimed.message_key, claimed.payload, claimed.created_at,
                claimed.partition_offset, mc.lease_generation
    """.query(MessageLogRow.codec)

  val renewLease: Command[(Duration, String, String, Int, Long)] =
    sql"""
      update message_consumer
      set locked_until = clock_timestamp() + $interval, updated_at = clock_timestamp()
      where topic = $varchar and consumer_id = $varchar and partition = $int4
        and lease_generation = $int8 and locked_until > clock_timestamp()
    """.command

  val verifyLease: Query[(String, String, Int, Long), Int] =
    sql"""
      select 1
      from message_consumer
      where topic = $varchar and consumer_id = $varchar and partition = $int4
        and lease_generation = $int8 and locked_until > clock_timestamp()
      for share
    """.query(int4)

  val commitOffset: Command[(Long, String, String, Int, Long, Long)] =
    sql"""
      update message_consumer
      set last_offset = $int8, locked_until = null, attempts = 0, last_error = null, updated_at = clock_timestamp()
      where topic = $varchar and consumer_id = $varchar and partition = $int4
        and lease_generation = $int8 and locked_until > clock_timestamp() and last_offset < $int8
    """.command

  val recordFailure: Command[(String, Duration, String, String, Int, Long)] =
    sql"""
      update message_consumer
      set attempts = attempts + 1, last_error = $text, locked_until = clock_timestamp() + $interval,
          lease_generation = lease_generation + 1, updated_at = clock_timestamp()
      where topic = $varchar and consumer_id = $varchar and partition = $int4
        and lease_generation = $int8 and locked_until > clock_timestamp()
    """.command

  // Delete only messages every consumer group has already processed on that partition, so nothing unread is ever lost.
  val purge: Command[(String, Duration)] =
    sql"""
      delete from message_log mq
      where mq.topic = $varchar
        and mq.partition_offset <= (
          select min(mc.last_offset)
          from message_consumer mc
          where mc.topic = mq.topic and mc.partition = mq.partition
        )
        and mq.created_at < now() - $interval
    """.command

/** Persistence for the durable log and the per-consumer-partition offsets that back the message bus. */
final private[messagebus] class MessageLog(pool: Resource[IO, Session[IO]]):

  private val maxBatchSize = 1000

  def enqueue(session: Session[IO], topic: String, partition: Int, messageKey: String, payload: Array[Byte]): IO[Unit] =
    session.prepare(MessageLogQueries.insert).flatMap(_.execute((topic, partition, messageKey, payload))).void

  def enqueueAll(session: Session[IO], topic: String, entries: Seq[(Int, String, Array[Byte])]): IO[Unit] =
    if entries.isEmpty then IO.unit
    else
      val ordered = entries
        .groupBy(_._1)
        .toList
        .sortBy(_._1)
        .flatMap(_._2)

      ordered.grouped(maxBatchSize).toList.traverse_ { chunk =>
        val rows = chunk
          .groupBy(_._1)
          .toList
          .sortBy(_._1)
          .flatMap { (partition, group) =>
            group.zipWithIndex.map { case ((_, messageKey, payload), idx) =>
              (partition, idx.toLong + 1L, messageKey, payload)
            }
          }
        session.prepare(MessageLogQueries.insertBatch(rows.size)).flatMap(_.execute((rows, topic))).void
      }

  def ensureConsumerAtLatest(topic: String, consumerId: String, partitions: Int): IO[Unit] =
    registerConsumer(topic, consumerId, partitions, None)

  def ensureConsumer(topic: String, consumerId: String, partitions: Int, initialOffset: Long): IO[Unit] =
    registerConsumer(topic, consumerId, partitions, Some(initialOffset))

  private def registerConsumer(topic: String, consumerId: String, partitions: Int, initialOffset: Option[Long]): IO[Unit] =
    pool.use(session => session.prepare(MessageLogQueries.ensureConsumer).flatMap(_.execute((topic, consumerId, partitions, initialOffset)))).void

  def claimNextUnprocessed(topic: String, consumerId: String, leaseDuration: Duration): IO[Option[MessageLogRow]] =
    pool.use(session => session.prepare(MessageLogQueries.claimNextUnprocessed).flatMap(_.option((leaseDuration, topic, consumerId))))

  def renewLease(consumerId: String, row: MessageLogRow, leaseDuration: Duration): IO[Boolean] =
    executeFenced(MessageLogQueries.renewLease, (leaseDuration, row.topic, consumerId, row.partition, row.leaseGeneration))

  def verifyLease(session: Session[IO], consumerId: String, row: MessageLogRow): IO[Boolean] =
    session
      .prepare(MessageLogQueries.verifyLease)
      .flatMap(_.option((row.topic, consumerId, row.partition, row.leaseGeneration)))
      .map(_.isDefined)

  def commitOffset(consumerId: String, row: MessageLogRow): IO[Boolean] =
    executeFenced(MessageLogQueries.commitOffset, (row.offset, row.topic, consumerId, row.partition, row.leaseGeneration, row.offset))

  def recordFailure(consumerId: String, row: MessageLogRow, error: String, retryBackoff: Duration): IO[Boolean] =
    executeFenced(MessageLogQueries.recordFailure, (error, retryBackoff, row.topic, consumerId, row.partition, row.leaseGeneration))

  private def executeFenced[A](command: Command[A], args: A): IO[Boolean] =
    pool.use(session => session.prepare(command).flatMap(_.execute(args))).map(_ == Completion.Update(1))

  def purge(topic: String, olderThan: Duration): IO[Unit] =
    pool.use(session => session.prepare(MessageLogQueries.purge).flatMap(_.execute((topic, olderThan))).void)

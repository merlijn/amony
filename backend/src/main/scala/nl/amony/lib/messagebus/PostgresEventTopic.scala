package nl.amony.lib.messagebus

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import fs2.Stream
import skunk.Session
import skunk.data.Identifier

/**
 * A [[EventTopic]] backed by the durable outbox table. Producers append a row and send a `NOTIFY` on the topic
 * channel in the same transaction; consumers wake on `NOTIFY`, claim batches with `FOR UPDATE SKIP LOCKED`, and
 * mark them processed. A periodic poll drains anything whose notification was lost and requeues work abandoned by
 * a crashed consumer once its claim TTL has elapsed.
 */
final private[messagebus] class PostgresEventTopic[E](
  key: EventTopicKey[E],
  outbox: EventOutbox,
  pool: Resource[IO, Session[IO]],
  config: EventBusConfig
) extends EventTopic[E]:

  private val codec      = key.persistenceCodec
  private val identifier = PostgresEventTopic.channelIdentifier(key.name)

  override def publish(event: E): IO[Unit] =
    pool.use(session => session.transaction.use(_ => publish(session, event)))

  override def publish(session: Session[IO], event: E): IO[Unit] =
    outbox.enqueue(session, key.name, codec.encode(event)) >> session.channel(identifier).notify(key.name)

  override def processAtLeastOnce(processorId: String, batchSize: Int)(processor: E => IO[Unit]): Stream[IO, Int] =
    val realtime = notifications.evalMap(_ => drain(batchSize)(processor))
    val polling  = Stream.fixedRateStartImmediately[IO](config.pollInterval).evalMap(_ => drain(batchSize)(processor))
    val purging  = Stream.fixedRateStartImmediately[IO](config.purgeInterval).evalMap(_ => outbox.purge(config.retentionDuration).as(0))
    realtime.merge(polling).merge(purging)

  private def notifications: Stream[IO, Unit] =
    Stream.resource(pool).flatMap(session => session.channel(identifier).listen(config.notificationQueueSize)).void

  private def drain(batchSize: Int)(processor: E => IO[Unit]): IO[Int] =
    outbox.claim(key.name, config.claimTtlDuration, batchSize).flatMap:
      case Nil  => IO.pure(0)
      case rows => rows.parTraverse_(process(processor)) >> drain(batchSize)(processor).map(rows.size + _)

  private def process(processor: E => IO[Unit])(row: OutboxRow): IO[Unit] =
    IO(codec.decode(row.payload)).flatMap(processor).attempt.flatMap:
      case Right(_) => outbox.markProcessed(row.id)
      case Left(e)  =>
        val error = Option(e.getMessage).getOrElse(e.getClass.getName)
        if row.attempts >= config.maxAttempts then outbox.markFailed(row.id, error)
        else outbox.releaseForRetry(row.id, error)

private[messagebus] object PostgresEventTopic:

  def channelIdentifier(name: String): Identifier =
    val sanitized = name.map(c => if c.isLetterOrDigit || c == '_' then c else '_')
    Identifier.fromString(s"amony_$sanitized").fold(message => throw new IllegalArgumentException(message), identity)

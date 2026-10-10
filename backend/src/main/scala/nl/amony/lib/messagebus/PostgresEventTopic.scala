package nl.amony.lib.messagebus

import cats.effect.std.Mutex
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import fs2.Stream
import skunk.Session
import skunk.data.Identifier

/**
 * An [[EventTopic]] backed by the durable outbox table. Producers append a row and send a `NOTIFY` on the topic
 * channel in the same transaction; the consumer wakes on `NOTIFY` (with a periodic poll as a fallback), claims the
 * oldest event with `FOR UPDATE SKIP LOCKED`, and processes it before the next, so events are applied in order.
 * Events left claimed by a stopped consumer are requeued on startup.
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

  override def processAtLeastOnce(processorId: String)(processor: E => IO[Unit]): Stream[IO, Unit] =
    Stream.eval(Mutex[IO]).flatMap { mutex =>
      // Any claimed event at startup was abandoned by a previous consumer (single-node assumption), so requeue it.
      val recover  = Stream.eval(outbox.resetClaimed(key.name))
      val realtime = notifications.evalMap(_ => mutex.lock.use(_ => drain(processor)))
      val polling  = Stream.fixedRateStartImmediately[IO](config.pollInterval).evalMap(_ => mutex.lock.use(_ => drain(processor)))
      val purging  = Stream.fixedRateStartImmediately[IO](config.purgeInterval).evalMap(_ => outbox.purge(config.retentionDuration))
      recover ++ realtime.merge(polling).merge(purging)
    }

  private def notifications: Stream[IO, Unit] =
    Stream.resource(pool).flatMap(session => session.channel(identifier).listen(config.notificationQueueSize)).void

  /** Drain the topic in order, stopping at the first event that fails so it is retried before anything newer. */
  private def drain(processor: E => IO[Unit]): IO[Unit] =
    outbox.claimOldest(key.name).flatMap {
      case None      => IO.unit
      case Some(row) => process(processor)(row).flatMap(advance => if advance then drain(processor) else IO.unit)
    }

  /** @return whether to advance to the next event (processed, or parked as failed) or to retry this one later. */
  private def process(processor: E => IO[Unit])(row: OutboxRow): IO[Boolean] =
    IO(codec.decode(row.payload)).attempt.flatMap:
      // An undecodable payload is a permanent error; park it so it does not block the topic forever.
      case Left(e)      => outbox.markFailed(row.id, Option(e.getMessage).getOrElse(e.getClass.getName)).as(true)
      // A processor failure (e.g. Solr unavailable) is transient; retry in place so nothing is skipped. The backoff
      // is applied while holding the consumer's mutex, so poll/notify triggers are delayed by it too.
      case Right(event) =>
        processor(event).attempt.flatMap:
          case Right(_) => outbox.markProcessed(row.id).as(true)
          case Left(e)  =>
            outbox.releaseForRetry(row.id, Option(e.getMessage).getOrElse(e.getClass.getName)) >> IO.sleep(config.retryBackoff).as(false)

private[messagebus] object PostgresEventTopic:

  def channelIdentifier(name: String): Identifier =
    val sanitized = name.map(c => if c.isLetterOrDigit || c == '_' then c else '_')
    Identifier.fromString(s"amony_$sanitized").fold(message => throw new IllegalArgumentException(message), identity)

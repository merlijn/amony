package nl.amony.lib.messagebus

import cats.effect.std.Mutex
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import fs2.Stream
import scribe.Logging
import skunk.Session
import skunk.data.Identifier

/**
 * A [[MessageTopic]] backed by the durable message log. Producers append a row and send a `NOTIFY` on the topic
 * channel in the same transaction; each consumer group wakes on `NOTIFY` (with a periodic poll as a fallback) and
 * reads the log from its own committed offset, in order, so one topic can be consumed independently by several
 * processors. A message is only deleted once every consumer group has processed it.
 */
final private[messagebus] class PostgresMessageTopic[E](
  key: MessageTopicKey[E],
  queue: MessageQueue,
  pool: Resource[IO, Session[IO]],
  config: MessageBusConfig
) extends MessageTopic[E] with Logging:

  private val codec      = key.codec
  private val identifier = PostgresMessageTopic.channelIdentifier(key.name)

  override def publish(message: E): IO[Unit] =
    pool.use(session => session.transaction.use(_ => publish(session, message)))

  override def publish(session: Session[IO], message: E): IO[Unit] =
    queue.enqueue(session, key.name, codec(message)) >> session.channel(identifier).notify(key.name)

  override def processAtLeastOnce(consumerId: String)(processor: E => IO[Unit]): Stream[IO, Unit] =
    Stream.eval(Mutex[IO]).flatMap { mutex =>
      // A new consumer group starts at the latest message, like Kafka's `auto.offset.reset=latest`.
      val register = Stream.eval(queue.latestId(key.name).flatMap(offset => queue.ensureConsumer(key.name, consumerId, offset)))
      val realtime = notifications.evalMap(_ => mutex.lock.use(_ => drain(consumerId, processor)))
      val polling  = Stream.fixedRateStartImmediately[IO](config.pollInterval).evalMap(_ => mutex.lock.use(_ => drain(consumerId, processor)))
      val purging  = Stream.fixedRateStartImmediately[IO](config.purgeInterval).evalMap(_ => queue.purge(key.name, config.retentionDuration))
      register ++ realtime.merge(polling).merge(purging)
    }

  private def notifications: Stream[IO, Unit] =
    Stream.resource(pool).flatMap(session => session.channel(identifier).listen(config.notificationQueueSize)).void

  /** Drain the log in order, stopping at the first message that fails so it is retried before anything newer. */
  private def drain(consumerId: String, processor: E => IO[Unit]): IO[Unit] =
    queue.nextUnprocessed(key.name, consumerId).flatMap {
      case None      => IO.unit
      case Some(row) => process(consumerId, processor)(row).flatMap(advance => if advance then drain(consumerId, processor) else IO.unit)
    }

  /** @return whether to advance to the next message (committed) or to retry this one later. */
  private def process(consumerId: String, processor: E => IO[Unit])(row: MessageQueueRow): IO[Boolean] =
    codec.decodeJson(row.payload).fold(e => IO.raiseError(e), e => IO.pure(e)).flatMap(processor).attempt.flatMap:
      case Right(_) => queue.commitOffset(key.name, consumerId, row.id).as(true)
      // Any failure (a processor error, or a payload that cannot be decoded) is retried in place, so nothing is ever
      // skipped. The backoff is applied while holding the consumer's mutex, so poll/notify triggers are delayed too.
      case Left(e)  =>
        val error = Option(e.getMessage).getOrElse(e.getClass.getName)
        IO(logger.error(s"Failed to process message ${row.id} on topic '${key.name}' for consumer '$consumerId': $error")) >>
          queue.recordFailure(key.name, consumerId, error) >> IO.sleep(config.retryBackoff).as(false)

private[messagebus] object PostgresMessageTopic:

  def channelIdentifier(name: String): Identifier =
    val sanitized = name.map(c => if c.isLetterOrDigit || c == '_' then c else '_')
    Identifier.fromString(s"amony_$sanitized").fold(message => throw new IllegalArgumentException(message), identity)

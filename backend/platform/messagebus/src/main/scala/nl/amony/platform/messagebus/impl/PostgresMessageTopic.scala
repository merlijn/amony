package nl.amony.platform.messagebus.impl

import scala.concurrent.duration.*

import cats.effect.std.Semaphore
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import fs2.Stream
import scribe.Logging
import skunk.Session
import skunk.data.Identifier

import nl.amony.platform.messagebus.*

/**
 * A [[MessageTopic]] backed by the durable message log. Producers append a row to a key-derived partition and send a
 * `NOTIFY` on the topic channel in the same transaction; each consumer group wakes on `NOTIFY` (with a periodic poll
 * as a fallback) and claims partitions via database leases (`FOR UPDATE SKIP LOCKED`), reading each partition from its
 * own committed offset in order. Multiple workers (within a node via `Semaphore` and across nodes) can run the same
 * consumer group concurrently without processing the same partition at the same time. A message is only deleted once
 * every consumer group has processed it.
 */
final private[messagebus] class PostgresMessageTopic[E](
  key: MessageTopicKey[E],
  log: MessageLog,
  pool: Resource[IO, Session[IO]],
  config: MessageBusConfig
) extends MessageTopic[E] with Logging:

  private val codec      = key.codec
  private val identifier = PostgresMessageTopic.channelIdentifier(key.name)

  override def publish(message: E): IO[Unit] =
    pool.use(session => session.transaction.use(_ => publish(session, message)))

  override def publish(session: Session[IO], message: E): IO[Unit] =
    val (messageKey, partition) = key.partitionFor(message)
    log.enqueue(session, key.name, partition, messageKey, codec(message)) >> session.channel(identifier).notify(key.name)

  override def processAtLeastOnce(consumerId: String, parallelism: Option[Int] = None)(processor: E => IO[Unit]): Stream[IO, Unit] =
    val maxWorkers = parallelism.getOrElse(config.parallelism).min(key.partitions).max(1)
    Stream.eval(Semaphore[IO](maxWorkers.toLong)).flatMap { semaphore =>
      val trigger: IO[Unit] =
        semaphore.tryAcquire.flatMap {
          case false => IO.unit
          case true  => drain(consumerId, processor).guarantee(semaphore.release)
        }

      // A new consumer group starts at the latest message, like Kafka's `auto.offset.reset=latest`.
      val register = Stream.eval(log.latestId(key.name).flatMap(offset => log.ensureConsumer(key.name, consumerId, key.partitions, offset)))
      val realtime = notifications.parEvalMapUnbounded(_ => trigger)
      val polling  = Stream
        .fixedRateStartImmediately[IO](config.pollInterval)
        .parEvalMapUnbounded(_ => List.fill(maxWorkers)(trigger).parSequence_)
      val purging  = Stream.fixedRateStartImmediately[IO](config.purgeInterval).evalMap(_ => log.purge(key.name, config.retentionDuration))
      register ++ realtime.merge(polling).merge(purging)
    }

  private def notifications: Stream[IO, Unit] =
    Stream.resource(pool).flatMap(session => session.channel(identifier).listen(config.notificationQueueSize)).void

  /**
   * Drain all available unlocked partitions for this consumer group. If a partition fails, its lease is set to
   * `now() + retryBackoff`, while other partitions with pending messages continue draining immediately.
   */
  private def drain(consumerId: String, processor: E => IO[Unit]): IO[Unit] =
    log.claimNextUnprocessed(key.name, consumerId, config.leaseJavaDuration).flatMap {
      case None      => IO.unit
      case Some(row) => process(consumerId, processor)(row) >> drain(consumerId, processor)
    }

  private def process(consumerId: String, processor: E => IO[Unit])(row: MessageLogRow): IO[Unit] =
    val renewalInterval = (config.leaseDuration / 3).max(50.millis)
    val heartbeat       = Stream.awakeEvery[IO](renewalInterval)
      .evalMap(_ => log.renewLease(key.name, consumerId, row.partition, config.leaseJavaDuration))
      .compile
      .drain

    val runProcessor = codec.decodeJson(row.payload).fold(e => IO.raiseError(e), e => IO.pure(e)).flatMap(processor)

    heartbeat.background.use(_ => runProcessor).attempt.flatMap:
      case Right(_) => log.commitOffset(key.name, consumerId, row.partition, row.id)
      // Any failure (a processor error, or a payload that cannot be decoded) sets the partition's lease to
      // `retryBackoff` so that partition backs off in place while other partitions remain runnable.
      case Left(e)  =>
        val error = Option(e.getMessage).getOrElse(e.getClass.getName)
        IO(logger.error(
          s"Failed to process message ${row.id} (partition ${row.partition}) on topic '${key.name}' for consumer '$consumerId': $error"
        )) >> log.recordFailure(key.name, consumerId, row.partition, error, config.retryBackoffDuration)

private[messagebus] object PostgresMessageTopic:

  def channelIdentifier(name: String): Identifier =
    val sanitized = name.map(c => if c.isLetterOrDigit || c == '_' then c else '_')
    Identifier.fromString(s"amony_$sanitized").fold(message => throw new IllegalArgumentException(message), identity)

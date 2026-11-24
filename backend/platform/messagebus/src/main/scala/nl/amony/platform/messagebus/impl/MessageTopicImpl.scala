package nl.amony.platform.messagebus.impl

import scala.concurrent.duration.*

import cats.effect.std.{Queue, Semaphore}
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
 * own committed offset in order. Each partition has one valid claim at a time; a worker that loses its lease cannot
 * change the replacement's lease or advance its offset. A message is only deleted once every registered consumer
 * group has acknowledged it.
 */
final private[messagebus] class MessageTopicImpl[E](
  key: MessageTopicKey[E],
  log: MessageLog,
  pool: Resource[IO, Session[IO]],
  config: MessageBusConfig
) extends MessageTopic[E] with Logging:

  private val identifier = MessageTopicImpl.channelIdentifier(key.name)

  override def publish(message: E): IO[Unit] =
    pool.use(session => session.transaction.use(_ => publish(session, message)))

  override def publish(session: Session[IO], message: E): IO[Unit] =
    val (messageKey, partition) = key.partitionFor(message)
    log.enqueue(session, key.name, partition, messageKey, key.encode(message)) >> session.channel(identifier).notify(key.name)

  override def publishAll(messages: Seq[E]): IO[Unit] =
    if messages.isEmpty then IO.unit
    else pool.use(session => session.transaction.use(_ => publishAll(session, messages)))

  override def publishAll(session: Session[IO], messages: Seq[E]): IO[Unit] =
    if messages.isEmpty then IO.unit
    else
      val entries = messages.map { message =>
        val (messageKey, partition) = key.partitionFor(message)
        (partition, messageKey, key.encode(message))
      }
      log.enqueueAll(session, key.name, entries) >> session.channel(identifier).notify(key.name)

  override def processAtLeastOnce(consumerId: String, parallelism: Option[Int] = None)(processor: E => IO[Unit]): Stream[IO, Unit] =
    processRaw(consumerId, parallelism)((_, decoded) => IO.fromEither(decoded).flatMap(processor))

  private[messagebus] def processRaw(
    consumerId: String,
    parallelism: Option[Int]    = None,
    initialOffset: Option[Long] = None
  )(processor: (MessageLogRow, Either[io.circe.Error, E]) => IO[Unit]): Stream[IO, Unit] =
    val maxWorkers = parallelism.getOrElse(config.parallelism).min(key.partitions).max(1)
    val context    = s"topic '${key.name}', consumer '$consumerId'"
    Stream.eval((Semaphore[IO](maxWorkers.toLong), Queue.bounded[IO, Unit](1)).tupled).flatMap { (semaphore, wakeups) =>
      val trigger = IO.uncancelable { poll =>
        semaphore.tryAcquire.flatMap {
          case false => IO.unit
          case true  =>
            poll(drain(consumerId, processor))
              .handleErrorWith(error => IO(logger.error(s"Worker error for $context", error)))
              .guarantee(semaphore.release)
        }
      }

      // A new consumer group starts at the latest message, like Kafka's `auto.offset.reset=latest`, unless overridden.
      val ensure   = initialOffset.fold(log.ensureConsumerAtLatest(key.name, consumerId, key.partitions))(
        log.ensureConsumer(key.name, consumerId, key.partitions, _)
      )
      val register = restartOnError(s"Registration for $context")(Stream.eval(ensure))
      val listener = restartOnError(s"Notification listener for $context")(
        notifications.evalMap(_ => wakeups.tryOffer(()).void) ++ Stream.raiseError[IO](new IllegalStateException("LISTEN stream ended"))
      )
      val realtime = Stream.repeatEval(wakeups.take).parEvalMapUnbounded(_ => trigger)
      val polling  = Stream
        .fixedRateStartImmediately[IO](config.pollInterval)
        .parEvalMapUnbounded(_ => List.fill(maxWorkers)(trigger).parSequence_)
      val workers  = restartOnError(s"Consumer worker for $context")(realtime.merge(polling))
      val purging  = restartOnError(s"Purge for $context")(
        Stream.fixedRateStartImmediately[IO](config.purgeInterval).evalMap(_ => log.purge(key.name, config.retentionDuration))
      )
      register ++ workers.merge(listener).merge(purging)
    }

  private def restartOnError(component: String)(stream: Stream[IO, Unit]): Stream[IO, Unit] =
    val initialDelay = 1.second
    val maxDelay     = 30.seconds

    def run(backoff: FiniteDuration): Stream[IO, Unit] =
      Stream.eval(IO.monotonic).flatMap { started =>
        stream.handleErrorWith { error =>
          Stream.eval(IO.monotonic).flatMap { now =>
            val delay = if now - started >= maxDelay then initialDelay else backoff
            Stream.eval(IO(logger.error(s"$component failed; retrying in $delay", error))) ++
              Stream.sleep_[IO](delay) ++ run((delay * 2).min(maxDelay))
          }
        }
      }

    run(initialDelay)

  private def notifications: Stream[IO, Unit] =
    Stream.resource(pool).flatMap { session =>
      Stream.resource(session.channel(identifier).listenR(config.notificationQueueSize)).flatMap(registered => Stream.emit(()) ++ registered.void)
    }

  /**
   * Drain all available unlocked partitions for this consumer group. If a partition fails, its lease is set to
   * `now() + retryBackoff`, while other partitions with pending messages continue draining immediately.
   */
  private def drain(consumerId: String, processor: (MessageLogRow, Either[io.circe.Error, E]) => IO[Unit]): IO[Unit] =
    log.claimNextUnprocessed(key.name, consumerId, config.leaseJavaDuration).flatMap {
      case None      => IO.unit
      case Some(row) => process(consumerId, processor)(row) >> drain(consumerId, processor)
    }

  private def process(consumerId: String, processor: (MessageLogRow, Either[io.circe.Error, E]) => IO[Unit])(row: MessageLogRow): IO[Unit] =
    val context         = s"message ${row.id} (partition ${row.partition}) on topic '${key.name}' for consumer '$consumerId'"
    val renewalInterval = (config.leaseDuration / 3).max(50.millis)
    val heartbeat       =
      def loop(deadline: FiniteDuration): IO[Unit] =
        for
          now <- IO.monotonic
          _   <- IO.sleep(renewalInterval.min((deadline - now).max(Duration.Zero)))
          at  <- IO.monotonic
          res <- log.renewLease(consumerId, row, config.leaseJavaDuration).attempt
          _   <- res match
                   case Right(true)  => loop(at + config.leaseDuration)
                   case Right(false) => IO.unit
                   case Left(error)  =>
                     IO(logger.warn(s"Failed to renew lease for $context: $error")) >>
                       IO.monotonic.flatMap(after => IO.whenA(after < deadline)(loop(deadline)))
        yield ()

      IO.monotonic.flatMap(started => loop(started + config.leaseDuration))

    val runProcessor = IO.defer(processor(row, key.decode(row.payload)))
    val finish       = runProcessor.attempt.flatMap {
      case Right(_) => log.commitOffset(consumerId, row)
      case Left(e)  =>
        val error = Option(e.getMessage).getOrElse(e.getClass.getName)
        IO(logger.error(s"Failed to process $context: $error")) >> log.recordFailure(consumerId, row, error, config.retryBackoffDuration)
    }.flatMap(updated => IO.unlessA(updated)(IO(logger.warn(s"Lost lease before recording outcome for $context"))))

    IO.race(heartbeat, finish).void

private[messagebus] object MessageTopicImpl:

  def channelIdentifier(name: String): Identifier =
    val sanitized = name.map(c => if c.isLetterOrDigit || c == '_' then c else '_')
    Identifier.fromString(s"amony_$sanitized").fold(message => throw new IllegalArgumentException(message), identity)

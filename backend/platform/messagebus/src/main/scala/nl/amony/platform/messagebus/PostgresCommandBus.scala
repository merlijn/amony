package nl.amony.platform.messagebus

import scala.concurrent.duration.*

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import fs2.Stream
import scribe.Logging
import skunk.Session

final private[messagebus] class CommandBusy(id: CommandId)
    extends Exception(s"Command ${id.value} is being processed by another consumer")

/**
 * Adds a producer-visible status sidecar to a [[MessageTopic]]. Execution (log, partitions, leases, notifications,
 * retry backoff) is entirely delegated to the topic; this class only records the per-command lifecycle and turns a
 * permanently failing command into a terminal failure so it cannot block its partition forever.
 */
final private[messagebus] class PostgresCommandBus[P](
  key: CommandTopicKey[P],
  topic: MessageTopic[Command[P]],
  log: MessageLog,
  store: CommandStore,
  pool: Resource[IO, Session[IO]],
  config: MessageBusConfig
) extends CommandBus[P] with Logging:

  override def submit(messageKey: String, payload: P): IO[CommandId] =
    CommandId.random.flatMap { id =>
      pool.use(session => session.transaction.use(_ => submit(session, id, messageKey, payload))).as(id)
    }

  override def submit(session: Session[IO], id: CommandId, messageKey: String, payload: P): IO[Unit] =
    store.insert(session, key.name, id, messageKey) >> topic.publish(session, Command(id, messageKey, payload))

  override def status(id: CommandId): IO[Option[CommandStatus]] =
    store.get(key.name, id)

  override def subscribe(id: CommandId, pollInterval: FiniteDuration = 200.millis): Stream[IO, CommandStatus] =
    Stream
      .fixedRateStartImmediately[IO](pollInterval)
      .evalMap(_ => status(id))
      .unNone
      .changes
      .takeThrough(!_.isTerminal)

  override def awaitCompletion(id: CommandId, timeout: FiniteDuration): IO[CommandStatus] =
    subscribe(id).compile.lastOrError.timeout(timeout)

  override def processAtLeastOnce(consumerId: String)(handler: (Session[IO], P) => IO[Unit]): Stream[IO, Unit] =
    processCommands(consumerId) { command =>
      // The handler's domain writes and the command's success commit together, so `succeeded` always means the domain
      // write is visible.
      pool.use { session =>
        session.transaction.use(_ => handler(session, command.payload) >> store.succeed(session, key.name, command.id))
      }
    }

  override def processAtLeastOnce[A](consumerId: String)(work: P => IO[A])(commit: (Session[IO], P, A) => IO[Unit]): Stream[IO, Unit] =
    processCommands(consumerId) { command =>
      work(command.payload).flatMap { result =>
        pool.use { session =>
          session.transaction.use(_ => commit(session, command.payload, result) >> store.succeed(session, key.name, command.id))
        }
      }
    }

  private def processCommands(consumerId: String)(run: Command[P] => IO[Unit]): Stream[IO, Unit] =
    // Unlike an event topic, a command topic must never skip commands that were submitted before its consumer group
    // first registered. `ensureConsumer` is a no-op once the group exists, so this only affects the first start.
    Stream.eval(log.ensureConsumer(key.name, consumerId, key.partitions, 0)) ++
      topic.processAtLeastOnce(consumerId) { command =>
        store.claim(key.name, command.id, key.staleAfterDuration).flatMap {
          case Claim.Done                                             => IO.unit
          case Claim.Missing                                          =>
            IO(logger.error(s"Command ${command.id.value} has no status row on topic '${key.name}', skipping"))
          case Claim.Busy                                             => IO.raiseError(CommandBusy(command.id))
          case Claim.Acquired(attempts) if attempts > key.maxAttempts =>
            store.fail(key.name, command.id, s"Gave up after ${key.maxAttempts} attempts")
          case Claim.Acquired(attempts)                               =>
            run(command).handleErrorWith { error =>
              val message = Option(error.getMessage).getOrElse(error.getClass.getName)
              error match
                case _: PermanentCommandFailure       =>
                  IO(logger.warn(s"Command ${command.id.value} failed on topic '${key.name}': $message")) >>
                    store.fail(key.name, command.id, message)
                case _ if attempts >= key.maxAttempts =>
                  // Terminal failure: swallow, so the consumer offset advances and the partition keeps moving.
                  IO(logger.error(s"Command ${command.id.value} failed permanently on topic '${key.name}': $message")) >>
                    store.fail(key.name, command.id, message)
                case _                                =>
                  // Handled failure: requeue and rethrow, so the message bus applies its retry backoff.
                  store.requeue(key.name, command.id, message) >> IO.raiseError(error)
            }
        }
      }.merge(
        Stream
          .fixedRateStartImmediately[IO](config.purgeInterval)
          .evalMap(_ => store.purge(key.name, key.retentionDuration))
      )

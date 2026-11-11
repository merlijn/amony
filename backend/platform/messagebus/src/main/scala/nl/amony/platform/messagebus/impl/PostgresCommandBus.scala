package nl.amony.platform.messagebus.impl

import scala.concurrent.duration.*

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import fs2.Stream
import scribe.Logging
import skunk.Session

import nl.amony.platform.messagebus.*

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

  override def processAtLeastOnce[E](handler: (Session[IO], P) => IO[Either[E, Unit]]): Stream[IO, Unit] =
    processCommands { command =>
      // The handler's domain writes and the command's success commit together, so `succeeded` always means the domain
      // write is visible.
      pool.use { session =>
        session.transaction.use { _ =>
          handler(session, command.payload).flatMap {
            case Left(error) => IO.pure(Left(error))
            case Right(())   => store.succeed(session, key.name, command.id).as(Right(()))
          }
        }
      }
    }

  override def processAtLeastOnce[E, A](work: P => IO[Either[E, A]])(
    commit: (Session[IO], P, A) => IO[Either[E, Unit]]
  ): Stream[IO, Unit] =
    processCommands { command =>
      work(command.payload).flatMap {
        case Left(error)   => IO.pure(Left(error))
        case Right(result) =>
          pool.use { session =>
            session.transaction.use { _ =>
              commit(session, command.payload, result).flatMap {
                case Left(error) => IO.pure(Left(error))
                case Right(())   => store.succeed(session, key.name, command.id).as(Right(()))
              }
            }
          }
      }
    }

  private def processCommands[E](run: Command[P] => IO[Either[E, Unit]]): Stream[IO, Unit] =
    // Unlike an event topic, a command topic has a single logical consumer group (keyed by the command topic name) and
    // must never skip commands that were submitted before that consumer group first registered. `ensureConsumer` is a
    // no-op once the group exists, so this only affects the first start.
    Stream.eval(log.ensureConsumer(key.name, key.name, key.partitions, 0)) ++
      topic.processAtLeastOnce(key.name) { command =>
        store.claim(key.name, command.id).flatMap {
          case Claim.Done                                             => IO.unit
          case Claim.Missing                                          =>
            IO(logger.error(s"Command ${command.id.value} has no status row on topic '${key.name}', skipping"))
          case Claim.Acquired(attempts) if attempts > key.maxAttempts =>
            store.fail(key.name, command.id, s"Gave up after ${key.maxAttempts} attempts")
          case Claim.Acquired(attempts)                               =>
            run(command).flatMap {
              case Right(())   => IO.unit
              case Left(error) =>
                val message = error.toString
                IO(logger.warn(s"Command ${command.id.value} failed on topic '${key.name}': $message")) >>
                  store.fail(key.name, command.id, message)
            }.handleErrorWith { error =>
              val message = Option(error.getMessage).getOrElse(error.getClass.getName)
              if attempts >= key.maxAttempts then
                // Terminal failure: swallow, so the consumer offset advances and the partition keeps moving.
                IO(logger.error(s"Command ${command.id.value} failed permanently on topic '${key.name}': $message")) >>
                  store.fail(key.name, command.id, message)
              else
                // Retryable failure: requeue and rethrow, so the message bus applies its retry backoff.
                store.requeue(key.name, command.id, message) >> IO.raiseError(error)
            }
        }
      }.merge(
        Stream
          .fixedRateStartImmediately[IO](config.purgeInterval)
          .evalMap(_ => store.purge(key.name, key.retentionDuration))
      )

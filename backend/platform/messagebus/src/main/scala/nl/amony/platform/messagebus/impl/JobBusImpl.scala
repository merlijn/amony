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
 * retry backoff) is entirely delegated to the topic; this class only records the per-job lifecycle and turns a
 * permanently failing job into a terminal failure so it cannot block its partition forever.
 */
final private[messagebus] class JobBusImpl[P](
  key: JobTopicKey[P],
  topic: MessageTopic[Job[P]],
  log: MessageLog,
  store: JobStore,
  pool: Resource[IO, Session[IO]],
  config: MessageBusConfig
) extends JobBus[P] with Logging:

  override def submit(messageKey: String, payload: P): IO[JobId] =
    JobId.random.flatMap { id =>
      pool.use(session => session.transaction.use(_ => submit(session, id, messageKey, payload))).as(id)
    }

  override def submit(session: Session[IO], id: JobId, messageKey: String, payload: P): IO[Unit] =
    store.insert(session, key.name, id, messageKey) >> topic.publish(session, Job(id, messageKey, payload))

  override def status(id: JobId): IO[Option[JobStatus]] =
    store.get(key.name, id)

  override def subscribe(id: JobId, pollInterval: FiniteDuration = 200.millis): Stream[IO, JobStatus] =
    Stream
      .fixedRateStartImmediately[IO](pollInterval)
      .evalMap(_ => status(id))
      .unNone
      .changes
      .takeThrough(!_.isTerminal)

  override def awaitCompletion(id: JobId, timeout: FiniteDuration): IO[JobStatus] =
    subscribe(id).compile.lastOrError.timeout(timeout)

  override def processAtLeastOnce[E](handler: (Session[IO], P) => IO[Either[E, Unit]]): Stream[IO, Unit] =
    processJobs { job =>
      // The handler's domain writes and the job's success commit together, so `succeeded` always means the domain
      // write is visible.
      pool.use { session =>
        session.transaction.use { _ =>
          handler(session, job.payload).flatMap {
            case Left(error) => IO.pure(Left(error))
            case Right(())   => store.succeed(session, key.name, job.id).as(Right(()))
          }
        }
      }
    }

  override def processAtLeastOnce[E](
    work: P => IO[Either[E, IO[Either[E, Unit]]]]
  ): Stream[IO, Unit] =
    processJobs { job =>
      work(job.payload).flatMap {
        case Left(error)   => IO.pure(Left(error))
        case Right(commit) =>
          commit.flatMap {
            case Left(error) => IO.pure(Left(error))
            case Right(())   => store.succeed(key.name, job.id).as(Right(()))
          }
      }
    }

  private def processJobs[E](run: Job[P] => IO[Either[E, Unit]]): Stream[IO, Unit] =
    // Unlike an event topic, a job topic has a single logical consumer group (keyed by the job topic name) and
    // must never skip jobs that were submitted before that consumer group first registered. `ensureConsumer` is a
    // no-op once the group exists, so this only affects the first start.
    Stream.eval(log.ensureConsumer(key.name, key.name, key.partitions, 0)) ++
      topic.processAtLeastOnce(key.name) { job =>
        store.claim(key.name, job.id).flatMap {
          case Claim.Done                                             => IO.unit
          case Claim.Missing                                          =>
            IO(logger.error(s"Job ${job.id.value} has no status row on topic '${key.name}', skipping"))
          case Claim.Acquired(attempts) if attempts > key.maxAttempts =>
            store.fail(key.name, job.id, s"Gave up after ${key.maxAttempts} attempts")
          case Claim.Acquired(attempts)                               =>
            run(job).flatMap {
              case Right(())   => IO.unit
              case Left(error) =>
                val message = error.toString
                IO(logger.warn(s"Job ${job.id.value} failed on topic '${key.name}': $message")) >>
                  store.fail(key.name, job.id, message)
            }.handleErrorWith { error =>
              val message = Option(error.getMessage).getOrElse(error.getClass.getName)
              if attempts >= key.maxAttempts then
                // Terminal failure: swallow, so the consumer offset advances and the partition keeps moving.
                IO(logger.error(s"Job ${job.id.value} failed permanently on topic '${key.name}': $message")) >>
                  store.fail(key.name, job.id, message)
              else
                // Retryable failure: requeue and rethrow, so the message bus applies its retry backoff.
                store.requeue(key.name, job.id, message) >> IO.raiseError(error)
            }
        }
      }.merge(
        Stream
          .fixedRateStartImmediately[IO](config.purgeInterval)
          .evalMap(_ => store.purge(key.name, key.retentionDuration))
      )

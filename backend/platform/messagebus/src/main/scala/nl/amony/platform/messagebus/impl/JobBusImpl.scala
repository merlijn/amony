package nl.amony.platform.messagebus.impl

import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.duration.*

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import fs2.Stream
import io.circe.parser.parse
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
  topic: MessageTopicImpl[Job[P]],
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
    processJobs { (row, job, attempts) =>
      // The handler's domain writes and the job's success commit together, fenced by the partition lease and attempt.
      pool.use { session =>
        session.transaction.use { tx =>
          handler(session, job.payload).flatMap {
            case Left(error) => tx.rollback.as(Left(error))
            case Right(())   =>
              for
                leaseValid <- log.verifyLease(session, key.name, row)
                succeeded  <- if leaseValid then store.succeed(session, key.name, job.id, attempts) else IO.pure(false)
                _          <- IO.unlessA(leaseValid && succeeded)(
                                tx.rollback >> IO(logger.warn(s"Lost lease or claim before committing job ${job.id.value} on topic '${key.name}'"))
                              )
              yield Right(())
          }
        }
      }
    }

  override def processAtLeastOnce[E](
    work: P => IO[Either[E, IO[Either[E, Unit]]]]
  ): Stream[IO, Unit] =
    processJobs { (row, job, attempts) =>
      work(job.payload).flatMap {
        case Left(error)   => IO.pure(Left(error))
        case Right(commit) =>
          pool.use(session => log.verifyLease(session, key.name, row)).flatMap {
            case false =>
              IO(logger.warn(s"Lost lease before committing job ${job.id.value} on topic '${key.name}'")).as(Right(()))
            case true  =>
              commit.flatMap {
                case Left(error) => IO.pure(Left(error))
                case Right(())   =>
                  store.succeed(key.name, job.id, attempts).flatMap { updated =>
                    IO.unlessA(updated)(IO(logger.warn(s"Lost claim before succeeding job ${job.id.value} on topic '${key.name}'")))
                      .as(Right(()))
                  }
              }
          }
      }
    }

  private def processJobs[E](run: (MessageLogRow, Job[P], Int) => IO[Either[E, Unit]]): Stream[IO, Unit] =
    // Unlike an event topic, a job topic has a single logical consumer group (keyed by the job topic name) and
    // starts from offset 0 so jobs submitted before the consumer first registers are never skipped.
    topic
      .processRaw(key.name, initialOffset = Some(0L)) { (row, decoded) =>
        decoded match
          case Left(decodeError) =>
            val message = s"Failed to decode job message ${row.id} on topic '${key.name}': ${decodeError.getMessage}"
            val failJob = extractJobId(row.payload).traverse_ { id =>
              store.claim(key.name, id).flatMap {
                case Claim.Acquired(attempts) => store.fail(key.name, id, message, attempts).void
                case _                        => IO.unit
              }
            }
            IO(logger.error(message)) >> failJob

          case Right(job) =>
            store.claim(key.name, job.id).flatMap {
              case Claim.Done                                             => IO.unit
              case Claim.Missing                                          =>
                IO(logger.error(s"Job ${job.id.value} has no status row on topic '${key.name}', skipping"))
              case Claim.Acquired(attempts) if attempts > key.maxAttempts =>
                store.fail(key.name, job.id, s"Gave up after ${key.maxAttempts} attempts", attempts).void
              case Claim.Acquired(attempts)                               =>
                run(row, job, attempts).flatMap {
                  case Right(())   => IO.unit
                  case Left(error) =>
                    val message = error.toString
                    IO(logger.warn(s"Job ${job.id.value} failed on topic '${key.name}': $message")) >>
                      store.fail(key.name, job.id, message, attempts).void
                }.handleErrorWith { error =>
                  val message = Option(error.getMessage).getOrElse(error.getClass.getName)
                  if attempts >= key.maxAttempts then
                    // Terminal failure: swallow, so the consumer offset advances and the partition keeps moving.
                    IO(logger.error(s"Job ${job.id.value} failed permanently on topic '${key.name}': $message")) >>
                      store.fail(key.name, job.id, message, attempts).void
                  else
                    // Retryable failure: requeue and rethrow, so the message bus applies its retry backoff.
                    store.requeue(key.name, job.id, message, attempts) >> IO.raiseError(error)
                }
            }
      }
      .merge(
        Stream
          .fixedRateStartImmediately[IO](config.purgeInterval)
          .evalMap(_ => store.purge(key.name, key.retentionDuration))
      )

  private def extractJobId(payload: Array[Byte]): Option[JobId] =
    parse(new String(payload, UTF_8)).toOption.flatMap(_.hcursor.get[String]("id").toOption).map(JobId(_))

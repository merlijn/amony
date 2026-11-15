package nl.amony.platform.messagebus.impl

import java.time.{Duration, OffsetDateTime}

import cats.effect.{IO, Resource}
import skunk.codec.all.*
import skunk.implicits.*
import skunk.{Codec, Command, Query, Session}

import nl.amony.platform.messagebus.{JobId, JobState, JobStatus}

private[messagebus] enum Claim:
  case Acquired(attempts: Int)
  case Done
  case Missing

final private[messagebus] case class JobRow(
  jobId: String,
  messageKey: String,
  state: String,
  attempts: Int,
  lastError: Option[String],
  updatedAt: OffsetDateTime
):
  def toStatus: JobStatus = JobStatus(JobId(jobId), messageKey, JobState.fromTag(state), attempts, lastError, updatedAt)

private[messagebus] object JobRow:
  val codec: Codec[JobRow] =
    (varchar(36) *: varchar(256) *: varchar(16) *: int4 *: text.opt *: timestamptz).to[JobRow]

private[messagebus] object JobStoreQueries:

  val insert: Command[(String, String, String)] =
    sql"insert into job_status (topic, job_id, message_key) values ($varchar, $varchar, $varchar)".command

  val get: Query[(String, String), JobRow] =
    sql"""select job_id, message_key, state, attempts, last_error, updated_at
          from job_status where topic = $varchar and job_id = $varchar""".query(JobRow.codec)

  /** Claims a non-terminal job. Returns the new attempt number. */
  val claim: Query[(String, String), Int] =
    sql"""update job_status
          set state = 'running', attempts = attempts + 1, updated_at = now()
          where topic = $varchar and job_id = $varchar
            and state in ('queued', 'running')
          returning attempts""".query(int4)

  val requeue: Command[(String, String, String)] =
    sql"""update job_status set state = 'queued', last_error = $text, updated_at = now()
          where topic = $varchar and job_id = $varchar""".command

  val succeed: Command[(String, String)] =
    sql"""update job_status set state = 'succeeded', last_error = null, updated_at = now()
          where topic = $varchar and job_id = $varchar""".command

  val fail: Command[(String, String, String)] =
    sql"""update job_status set state = 'failed', last_error = $text, updated_at = now()
          where topic = $varchar and job_id = $varchar""".command

  val purge: Command[(String, Duration)] =
    sql"""delete from job_status
          where topic = $varchar and state in ('succeeded', 'failed') and updated_at < now() - $interval""".command

/** Persistence for the job status sidecar. */
final private[messagebus] class JobStore(pool: Resource[IO, Session[IO]]):

  def insert(session: Session[IO], topic: String, id: JobId, key: String): IO[Unit] =
    session.prepare(JobStoreQueries.insert).flatMap(_.execute((topic, id.value, key))).void

  def get(topic: String, id: JobId): IO[Option[JobStatus]] =
    pool.use(session => session.prepare(JobStoreQueries.get).flatMap(_.option((topic, id.value))).map(_.map(_.toStatus)))

  def claim(topic: String, id: JobId): IO[Claim] =
    pool.use { session =>
      session.prepare(JobStoreQueries.claim).flatMap(_.option((topic, id.value))).flatMap {
        case Some(attempts) => IO.pure(Claim.Acquired(attempts))
        case None           =>
          session.prepare(JobStoreQueries.get).flatMap(_.option((topic, id.value))).map {
            case None    => Claim.Missing
            case Some(_) => Claim.Done
          }
      }
    }

  def requeue(topic: String, id: JobId, error: String): IO[Unit] =
    pool.use(_.prepare(JobStoreQueries.requeue).flatMap(_.execute((error, topic, id.value)))).void

  def succeed(session: Session[IO], topic: String, id: JobId): IO[Unit] =
    session.prepare(JobStoreQueries.succeed).flatMap(_.execute((topic, id.value))).void

  def succeed(topic: String, id: JobId): IO[Unit] =
    pool.use(session => succeed(session, topic, id))

  def fail(topic: String, id: JobId, error: String): IO[Unit] =
    pool.use(_.prepare(JobStoreQueries.fail).flatMap(_.execute((error, topic, id.value)))).void

  def purge(topic: String, retention: Duration): IO[Unit] =
    pool.use(_.prepare(JobStoreQueries.purge).flatMap(_.execute((topic, retention)))).void

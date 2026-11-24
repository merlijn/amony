package nl.amony.platform.messagebus

import java.util.UUID
import scala.concurrent.duration.*

import cats.Eq
import cats.effect.IO
import fs2.Stream
import io.circe.Codec as CirceCodec
import skunk.Session

opaque type JobId = String

object JobId:
  def random: IO[JobId]                   = IO(UUID.randomUUID().toString)
  def apply(value: String): JobId         = value
  extension (id: JobId) def value: String = id
  given CirceCodec[JobId]                 = CirceCodec.implied[String]

/** A job as stored in the message log. `key` is both the partition key and the per-aggregate ordering key. */
final case class Job[P](id: JobId, key: String, payload: P)

enum JobState:
  case Queued, Running, Succeeded, Failed

  def isTerminal: Boolean = this match
    case Succeeded | Failed => true
    case _                  => false

  def tag: String = this match
    case Queued    => "queued"
    case Running   => "running"
    case Succeeded => "succeeded"
    case Failed    => "failed"

object JobState:
  def fromTag(tag: String): JobState = tag match
    case "queued"    => Queued
    case "running"   => Running
    case "succeeded" => Succeeded
    case "failed"    => Failed
    case other       => throw new IllegalArgumentException(s"Unknown job state '$other'")

/** Producer-visible state of a single submitted job. */
final case class JobStatus(
  id: JobId,
  key: String,
  state: JobState,
  attempts: Int,
  lastError: Option[String],
  updatedAt: java.time.OffsetDateTime
):
  def isTerminal: Boolean = state.isTerminal

object JobStatus:
  given Eq[JobStatus] = Eq.fromUniversalEquals

/**
 * Static description of one job topic. One instance per job family, created at startup, so job topics are
 * long-lived and never leak per request.
 */
final case class JobTopicKey[P: CirceCodec](
  name: String,
  partitions: Int           = 8,
  maxAttempts: Int          = 5,
  // How long a terminal job stays observable after completion.
  retention: FiniteDuration = 7.days
):
  require(partitions >= 1, s"partitions must be >= 1, got $partitions")
  require(maxAttempts >= 1, s"maxAttempts must be >= 1, got $maxAttempts")

  private val jobCodec: CirceCodec[Job[P]] =
    CirceCodec.forProduct3("id", "key", "payload")((id: JobId, key: String, payload: P) => Job(id, key, payload))(job =>
      (job.id, job.key, job.payload)
    )

  val topicKey: MessageTopicKey[Job[P]] = MessageTopicKey[Job[P]](name, _.key, partitions)(using jobCodec)

  def retentionDuration: java.time.Duration = java.time.Duration.ofMillis(retention.toMillis)

/** Durable, at-least-once job queue on top of a [[MessageTopic]] with a producer-visible status sidecar. */
trait JobBus[P]:

  /** Publish a job in its own transaction and return its id. */
  def submit(key: String, payload: P): IO[JobId]

  /** Publish a job in the caller's transaction using a caller-generated id. */
  def submit(session: Session[IO], id: JobId, key: String, payload: P): IO[Unit]

  /** Return the current status of a job, if it exists and has not been purged. */
  def status(id: JobId): IO[Option[JobStatus]]

  /** Emits the status each time it changes, ending after the terminal state. */
  def subscribe(id: JobId, pollInterval: FiniteDuration = 200.millis): Stream[IO, JobStatus]

  /** Waits for the terminal state, raising on timeout. */
  def awaitCompletion(id: JobId, timeout: FiniteDuration): IO[JobStatus]

  /**
   * Consume jobs at least once (handler must be idempotent). Returning `Right(())` commits the handler's session writes
   * together with `Succeeded`; `Left(error)` records an immediate terminal failure; uncaught exceptions retry up to
   * `maxAttempts`.
   */
  def processAtLeastOnce[E](handler: (Session[IO], P) => IO[Either[E, Unit]]): Stream[IO, Unit]

  /**
   * Two-phase at-least-once consumer separating slow `work` from a final commit step. `Left(error)` in either phase
   * fails immediately without retrying; uncaught exceptions retry up to `maxAttempts`.
   */
  def processAtLeastOnce[E](work: P => IO[Either[E, IO[Either[E, Unit]]]]): Stream[IO, Unit]

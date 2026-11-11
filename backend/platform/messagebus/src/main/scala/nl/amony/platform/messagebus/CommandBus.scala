package nl.amony.platform.messagebus

import java.util.UUID
import scala.concurrent.duration.*

import cats.Eq
import cats.effect.IO
import fs2.Stream
import io.circe.Codec as CirceCodec
import skunk.Session

opaque type CommandId = String

object CommandId:
  def random: IO[CommandId]                   = IO(UUID.randomUUID().toString)
  def apply(value: String): CommandId         = value
  extension (id: CommandId) def value: String = id
  given CirceCodec[CommandId]                 = CirceCodec.implied[String]

/** A command as stored in the message log. `key` is both the partition key and the per-aggregate ordering key. */
final case class Command[P](id: CommandId, key: String, payload: P)

enum CommandState:
  case Queued, Running, Succeeded, Failed

  def isTerminal: Boolean = this match
    case Succeeded | Failed => true
    case _                  => false

  def tag: String = this match
    case Queued    => "queued"
    case Running   => "running"
    case Succeeded => "succeeded"
    case Failed    => "failed"

object CommandState:
  def fromTag(tag: String): CommandState = tag match
    case "queued"    => Queued
    case "running"   => Running
    case "succeeded" => Succeeded
    case "failed"    => Failed
    case other       => throw new IllegalArgumentException(s"Unknown command state '$other'")

/** Producer-visible state of a single submitted command. */
final case class CommandStatus(
  id: CommandId,
  key: String,
  state: CommandState,
  attempts: Int,
  lastError: Option[String],
  updatedAt: java.time.OffsetDateTime
):
  def isTerminal: Boolean = state.isTerminal

object CommandStatus:
  given Eq[CommandStatus] = Eq.fromUniversalEquals

/**
 * Static description of one command topic. One instance per command family, created at startup, so command topics are
 * long-lived and never leak per request.
 */
final case class CommandTopicKey[P: CirceCodec](
  name: String,
  partitions: Int           = 8,
  maxAttempts: Int          = 5,
  // How long a terminal command stays observable after completion.
  retention: FiniteDuration = 7.days
):
  require(partitions >= 1, s"partitions must be >= 1, got $partitions")
  require(maxAttempts >= 1, s"maxAttempts must be >= 1, got $maxAttempts")

  private val commandCodec: CirceCodec[Command[P]] =
    CirceCodec.forProduct3("id", "key", "payload")((id: CommandId, key: String, payload: P) => Command(id, key, payload))(command =>
      (command.id, command.key, command.payload)
    )

  val topicKey: MessageTopicKey[Command[P]] = MessageTopicKey[Command[P]](name, _.key, partitions)(using commandCodec)

  def retentionDuration: java.time.Duration = java.time.Duration.ofMillis(retention.toMillis)

/**
 * A durable command queue on top of a [[MessageTopic]]: commands are executed at least once, and their progress is
 * observable through [[status]] and [[subscribe]]. It does not change [[MessageTopic]]; it only adds a status sidecar
 * and wraps the consumer.
 */
trait CommandBus[P]:

  /** Publish a command in its own transaction and return its id. */
  def submit(key: String, payload: P): IO[CommandId]

  /**
   * Publish a command using the caller's session, so it becomes visible only if the caller's own writes commit. The
   * caller generates the id, so it can be referenced before the transaction commits.
   */
  def submit(session: Session[IO], id: CommandId, key: String, payload: P): IO[Unit]

  def status(id: CommandId): IO[Option[CommandStatus]]

  /** Emits the status each time it changes, ending after the terminal state. */
  def subscribe(id: CommandId, pollInterval: FiniteDuration = 200.millis): Stream[IO, CommandStatus]

  /** Waits for the terminal state, raising on timeout. */
  def awaitCompletion(id: CommandId, timeout: FiniteDuration): IO[CommandStatus]

  /**
   * At-least-once consumer. The handler must be idempotent: it can re-run after a crash or when the terminal write
   * committed but the consumer offset did not. Returning `Right(())` asserts the command's postcondition; returning
   * `Left(error)` records an immediate terminal failure without retrying; any uncaught exception in `IO` is retried up
   * to `maxAttempts`.
   *
   * The handler receives the session of a transaction that also records the terminal state, so its domain writes and
   * the command's success commit together.
   */
  def processAtLeastOnce[E](handler: (Session[IO], P) => IO[Either[E, Unit]]): Stream[IO, Unit]

  /**
   * At-least-once consumer with a split between slow, non-transactional work and a fast transactional commit. `work`
   * may run for a long time and is retried on uncaught `IO` failure; `Left(error)` from either `work` or `commit` fails
   * the command immediately without retrying; `commit` runs in the same transaction as the terminal state, so its
   * writes are atomic with the command's success.
   */
  def processAtLeastOnce[E, A](work: P => IO[Either[E, A]])(commit: (Session[IO], P, A) => IO[Either[E, Unit]]): Stream[IO, Unit]

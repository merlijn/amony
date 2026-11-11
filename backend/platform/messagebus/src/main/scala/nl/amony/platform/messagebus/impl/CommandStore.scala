package nl.amony.platform.messagebus.impl

import java.time.{Duration, OffsetDateTime}

import cats.effect.{IO, Resource}
import skunk.codec.all.*
import skunk.implicits.*
import skunk.{Codec, Command as SkunkCommand, Query, Session}

import nl.amony.platform.messagebus.{CommandId, CommandState, CommandStatus}

private[messagebus] enum Claim:
  case Acquired(attempts: Int)
  case Done
  case Missing

final private[messagebus] case class CommandRow(
  commandId: String,
  messageKey: String,
  state: String,
  attempts: Int,
  lastError: Option[String],
  updatedAt: OffsetDateTime
):
  def toStatus: CommandStatus = CommandStatus(CommandId(commandId), messageKey, CommandState.fromTag(state), attempts, lastError, updatedAt)

private[messagebus] object CommandRow:
  val codec: Codec[CommandRow] =
    (varchar(36) *: varchar(256) *: varchar(16) *: int4 *: text.opt *: timestamptz).to[CommandRow]

private[messagebus] object CommandStoreQueries:

  val insert: SkunkCommand[(String, String, String)] =
    sql"insert into command_status (topic, command_id, message_key) values ($varchar, $varchar, $varchar)".command

  val get: Query[(String, String), CommandRow] =
    sql"""select command_id, message_key, state, attempts, last_error, updated_at
          from command_status where topic = $varchar and command_id = $varchar""".query(CommandRow.codec)

  /** Claims a non-terminal command. Returns the new attempt number. */
  val claim: Query[(String, String), Int] =
    sql"""update command_status
          set state = 'running', attempts = attempts + 1, updated_at = now()
          where topic = $varchar and command_id = $varchar
            and state in ('queued', 'running')
          returning attempts""".query(int4)

  val requeue: SkunkCommand[(String, String, String)] =
    sql"""update command_status set state = 'queued', last_error = $text, updated_at = now()
          where topic = $varchar and command_id = $varchar""".command

  val succeed: SkunkCommand[(String, String)] =
    sql"""update command_status set state = 'succeeded', last_error = null, updated_at = now()
          where topic = $varchar and command_id = $varchar""".command

  val fail: SkunkCommand[(String, String, String)] =
    sql"""update command_status set state = 'failed', last_error = $text, updated_at = now()
          where topic = $varchar and command_id = $varchar""".command

  val purge: SkunkCommand[(String, Duration)] =
    sql"""delete from command_status
          where topic = $varchar and state in ('succeeded', 'failed') and updated_at < now() - $interval""".command

/** Persistence for the command status sidecar. */
final private[messagebus] class CommandStore(pool: Resource[IO, Session[IO]]):

  def insert(session: Session[IO], topic: String, id: CommandId, key: String): IO[Unit] =
    session.prepare(CommandStoreQueries.insert).flatMap(_.execute((topic, id.value, key))).void

  def get(topic: String, id: CommandId): IO[Option[CommandStatus]] =
    pool.use(session => session.prepare(CommandStoreQueries.get).flatMap(_.option((topic, id.value))).map(_.map(_.toStatus)))

  def claim(topic: String, id: CommandId): IO[Claim] =
    pool.use { session =>
      session.prepare(CommandStoreQueries.claim).flatMap(_.option((topic, id.value))).flatMap {
        case Some(attempts) => IO.pure(Claim.Acquired(attempts))
        case None           =>
          session.prepare(CommandStoreQueries.get).flatMap(_.option((topic, id.value))).map {
            case None    => Claim.Missing
            case Some(_) => Claim.Done
          }
      }
    }

  def requeue(topic: String, id: CommandId, error: String): IO[Unit] =
    pool.use(_.prepare(CommandStoreQueries.requeue).flatMap(_.execute((error, topic, id.value)))).void

  def succeed(session: Session[IO], topic: String, id: CommandId): IO[Unit] =
    session.prepare(CommandStoreQueries.succeed).flatMap(_.execute((topic, id.value))).void

  def fail(topic: String, id: CommandId, error: String): IO[Unit] =
    pool.use(_.prepare(CommandStoreQueries.fail).flatMap(_.execute((error, topic, id.value)))).void

  def purge(topic: String, retention: Duration): IO[Unit] =
    pool.use(_.prepare(CommandStoreQueries.purge).flatMap(_.execute((topic, retention)))).void

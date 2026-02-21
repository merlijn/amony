package nl.amony.modules.auth.dal

import java.time.OffsetDateTime

import cats.effect.{IO, Resource}
import io.circe.KeyEncoder.encodeKeyLong
import skunk.*
import skunk.Session
import skunk.codec.all.*
import skunk.implicits.*

class OAuthStateDatabase(pool: Resource[IO, Session[IO]]):

  val insertQuery: skunk.Command[OAuthStateRow] =
    sql"""
      INSERT INTO oauth_state (id, provider, created_at)
      VALUES ($int8, ${varchar(64)}, $timestamptz)
    """.command.to[OAuthStateRow]

  val getByIdQuery: skunk.Query[Long, OAuthStateRow] =
    sql"SELECT ${OAuthStateRow.columns} FROM oauth_state WHERE id = $int8".query(OAuthStateRow.decoder)

  val deleteQuery: skunk.Command[Long] =
    sql"DELETE FROM oauth_state WHERE id = $int8".command

  val deleteExpiredQuery: skunk.Command[OffsetDateTime] =
    sql"DELETE FROM oauth_state WHERE created_at < $timestamptz".command

  def insert(state: OAuthStateRow): IO[Unit] =
    pool.use(_.prepare(insertQuery).flatMap(_.execute(state))).map(_ => ())

  def getById(id: Long): IO[Option[OAuthStateRow]] =
    pool.use(_.prepare(getByIdQuery).flatMap(_.option(id)))

  def delete(id: Long): IO[Unit] =
    pool.use(_.prepare(deleteQuery).flatMap(_.execute(id))).map(_ => ())

  def deleteExpired(olderThan: OffsetDateTime): IO[Unit] =
    pool.use(_.prepare(deleteExpiredQuery).flatMap(_.execute(olderThan))).map(_ => ())

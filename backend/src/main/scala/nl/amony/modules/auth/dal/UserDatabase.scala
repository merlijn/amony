package nl.amony.modules.auth.dal

import cats.effect.{IO, Resource}
import scribe.Logging
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*
import skunk.{Decoder, Session}

class UserDatabase(pool: Resource[IO, Session[IO]]) extends Logging:

  private def useSession[A](s: Session[IO] => IO[A]): IO[A]                        = pool.use(s)
  private def useTransaction[A](f: (Session[IO], Transaction[IO]) => IO[A]): IO[A] = pool.use(s => s.transaction.use(tx => f(s, tx)))

  val getByIdQuery: skunk.Query[String, UserRow] =
    sql"SELECT id, email, auth_provider, auth_subject, time_registered, roles FROM users WHERE id = ${varchar(64)}".query(UserRow.decoder)

  val getByEmailQuery: skunk.Query[String, UserRow] =
    sql"SELECT id, email, auth_provider, auth_subject, time_registered, roles FROM users WHERE email = ${varchar(64)}".query(UserRow.decoder)

  val insertQuery: skunk.Command[UserRow] =
    sql"""
      INSERT INTO users (id, email, auth_provider, auth_subject, time_registered, roles)
      VALUES (${varchar(64)}, ${varchar(64)}, ${varchar(64)}, ${varchar(64)}, $timestamptz, $_varchar)
    """.command.to[UserRow]

  def getById(userId: String): IO[Option[UserRow]] =
    pool.use(_.prepare(getByIdQuery).flatMap(_.option(userId)))

  def getByEmail(email: String): IO[Option[UserRow]] =
    pool.use(_.prepare(getByEmailQuery).flatMap(_.option(email)))

  def insert(user: UserRow): IO[Unit] =
    pool.use(_.prepare(insertQuery).flatMap(_.execute(user))).map(_ => ())

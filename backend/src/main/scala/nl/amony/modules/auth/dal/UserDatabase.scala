package nl.amony.modules.auth.dal

import cats.effect.{IO, Resource}
import scribe.Logging
import skunk.Session

case class UserRow(id: String, email: String, oauth_provider: String, oauth_subject: String)

class UserDatabase(pool: Resource[IO, Session[IO]]) extends Logging:

  def getById(userId: String): IO[Option[UserRow]] = ???

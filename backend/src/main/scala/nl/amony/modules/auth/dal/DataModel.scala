package nl.amony.modules.auth.dal

import java.time.{OffsetDateTime, ZoneOffset}

import skunk.Decoder
import skunk.codec.all.*
import skunk.implicits.sql

import nl.amony.modules.auth.api.{User, UserId}

case class OAuthStateRow(id: Long, provider: String, created_at: OffsetDateTime)

object OAuthStateRow {
  val columns                         = sql"id, provider, created_at"
  val decoder: Decoder[OAuthStateRow] = (int8 *: varchar(64) *: timestamptz).to[OAuthStateRow]
}

case class UserRow(id: String, email: String, oauth_provider: String, oauth_subject: String, time_registered: OffsetDateTime) {

  def toUser: User =
    User(
      id             = UserId(id),
      email          = email,
      authProvider   = oauth_provider,
      authSubject    = oauth_subject,
      timeRegistered = time_registered.toInstant
    )
}

object UserRow {

  val columns = sql"id, email, auth_provider, auth_subject, time_registered"

  val decoder: Decoder[UserRow] =
    (varchar(64) *: varchar(64) *: varchar(64) *: varchar(64) *: timestamptz).to[UserRow]

  def fromUser(user: User): UserRow =
    UserRow(
      id              = user.id,
      email           = user.email,
      oauth_provider  = user.authProvider,
      oauth_subject   = user.authSubject,
      time_registered = user.timeRegistered.atOffset(ZoneOffset.UTC)
    )
}

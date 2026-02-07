package nl.amony.modules.auth.dal

import java.time.{Instant, OffsetDateTime, ZoneOffset}

import skunk.Decoder
import skunk.codec.all.*
import skunk.data.Arr
import skunk.implicits.sql

import nl.amony.modules.auth.api.{Role, User, UserId}

case class UserRow(id: String, email: String, oauth_provider: String, oauth_subject: String, time_registered: OffsetDateTime, roles: Arr[String]) {

  def toUser: User =
    User(
      id             = UserId(id),
      email          = email,
      authProvider   = oauth_provider,
      authSubject    = oauth_subject,
      timeRegistered = time_registered.toInstant,
      roles          = roles.flattenTo(Set).map(Role(_))
    )
}

object UserRow {

  val columns = sql"id, email, auth_provider, auth_subject, time_registered, roles"

  val decoder: Decoder[UserRow] =
    (varchar(64) *: varchar(64) *: varchar(64) *: varchar(64) *: timestamptz *: _varchar).to[UserRow]

  def fromUser(user: User): UserRow =
    UserRow(
      id              = user.id,
      email           = user.email,
      oauth_provider  = user.authProvider,
      oauth_subject   = user.authSubject,
      time_registered = user.timeRegistered.atOffset(ZoneOffset.UTC),
      roles           = Arr.fromFoldable(user.roles.toList.sorted)
    )
}

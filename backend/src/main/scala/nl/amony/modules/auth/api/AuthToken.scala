package nl.amony.modules.auth.api

import io.circe.Codec
import sttp.tapir.Schema
import sttp.tapir.Schema.annotations.customise

def required[T](s: Schema[T]) = s.copy(isOptional = false)

case class AuthToken(
  userId: UserId,
  @customise(required)
  roles: Set[Role]
) derives Codec, sttp.tapir.Schema {
  def isAnonymous: Boolean = roles.contains(Role.Anonymous)
}

object AuthToken {
  val anonymous: AuthToken = AuthToken(userId = UserId.anonymous, roles = Set(Role.Anonymous))
}

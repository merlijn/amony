package nl.amony.modules.auth.api

import io.circe.Codec
import sttp.tapir.Schema.annotations.customise

import nl.amony.lib.tapir.required

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

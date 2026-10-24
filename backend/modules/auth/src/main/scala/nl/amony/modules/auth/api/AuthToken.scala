package nl.amony.modules.auth.api

import io.circe.Codec
import sttp.tapir.Schema.annotations.customise

import nl.amony.platform.tapir.required

case class AuthToken(
  userId: UserId,
  @customise(required)
  roles: Set[Role]
) derives Codec, sttp.tapir.Schema {
  def isAnonymous: Boolean = roles.contains(Role.Anonymous)

  def hasPermission(permission: Permission)(using apiSecurity: ApiSecurity): Boolean =
    apiSecurity.hasPermission(this, permission)

  def canAccessBucket(requiredRole: Option[Role])(using apiSecurity: ApiSecurity): Boolean =
    apiSecurity.canAccessBucket(this, requiredRole)
}

object AuthToken {
  val anonymous: AuthToken = AuthToken(userId = UserId.anonymous, roles = Set(Role.Anonymous))
}

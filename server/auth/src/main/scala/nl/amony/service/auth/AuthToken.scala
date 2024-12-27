package nl.amony.service.auth

import io.circe.Codec

case class AuthToken(
  userId: String,
  roles: Set[Role],
) derives Codec

object AuthToken {
  val anonymous: AuthToken = AuthToken(
    userId = "anonymous",
    roles = Set.empty
  )
}

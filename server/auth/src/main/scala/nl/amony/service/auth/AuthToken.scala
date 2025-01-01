package nl.amony.service.auth

case class AuthToken(
  userId: String,
  roles: Set[Role],
)

object AuthToken {
  val anonymous: AuthToken = AuthToken(
    userId = "anonymous",
    roles = Set.empty
  )
}

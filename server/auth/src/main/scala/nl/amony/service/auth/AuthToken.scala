package nl.amony.service.auth

case class AuthToken(
  userId: String,
  roles: Set[Role],
)

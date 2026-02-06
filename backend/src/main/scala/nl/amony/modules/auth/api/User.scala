package nl.amony.modules.auth.api

case class User(
  id: UserId,
  provider: String,
  email: String,
  roles: Set[Role]
)

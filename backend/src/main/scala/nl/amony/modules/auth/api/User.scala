package nl.amony.modules.auth.api

case class User(
  id: UserId,
  authProvider: String,
  authSubject: String,
  email: String,
  timeRegistered: java.time.Instant,
  roles: Set[Role]
)

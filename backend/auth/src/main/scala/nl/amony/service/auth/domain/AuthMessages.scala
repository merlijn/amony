package nl.amony.service.auth.domain

case class Credentials(
  username: String,
  password: String
)

sealed trait AuthenticationResponse

case class Authentication(
  accessToken: String,
  refreshToken: String
) extends AuthenticationResponse

case class InvalidCredentials() extends AuthenticationResponse

case class GetByExternalId(
  externalId: String
)

case class UpsertUserRequest(
  externalId: String,
  password: String
)

case class User(
  userId: String,
  externalId: String,
  passwordHash: String
)

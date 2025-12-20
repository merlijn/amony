package nl.amony.service.auth

import cats.effect.IO
import nl.amony.lib.auth.TokenManager
import nl.amony.service.auth.domain.*
import scribe.Logging

class AuthServiceImpl(config: AuthConfig) extends AuthService with Logging {

  private val tokenManager = new TokenManager(config.jwt)

  // https://github.com/Password4j/password4j

  override def authenticate(request: Credentials): IO[AuthenticationResponse] =
    if (request.username == config.adminUsername && request.password == config.adminPassword) {
      val (accessToken, refreshToken) = tokenManager.createAccessAndRefreshTokens(Some(request.username), Set("admin"))
      IO.pure(Authentication(accessToken, refreshToken))
    } else
      IO.pure(InvalidCredentials())

  override def insertUser(request: UpsertUserRequest): IO[User] =
    IO.raiseError(new RuntimeException("Not implemented"))

  override def getByExternalId(request: GetByExternalId): IO[User] =
    IO.raiseError(new RuntimeException("Not implemented"))

  override def refresh(request: Authentication): IO[AuthenticationResponse] =
    tokenManager.refreshAccessToken(request.refreshToken) match
      case Some((accessToken, refreshToken)) => IO.pure(Authentication(accessToken, refreshToken))
      case None                              => IO.pure(InvalidCredentials())
}

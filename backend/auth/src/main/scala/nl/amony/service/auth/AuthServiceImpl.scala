package nl.amony.service.auth

import cats.effect.IO
import nl.amony.lib.auth.TokenManager
import nl.amony.service.auth.domain.*
import scribe.Logging

class AuthServiceImpl(config: AuthConfig) extends AuthService with Logging {

  private val tokenManager = new TokenManager(config.jwt)

  // https://github.com/Password4j/password4j

  override def authenticate(username: String, password: String): IO[AuthenticationResponse] =
    if (username == config.adminUsername && password == config.adminPassword) {
      val (accessToken, refreshToken) = tokenManager.createAccessAndRefreshTokens(Some(username), Set("admin"))
      IO.pure(Authentication(accessToken, refreshToken))
    } else
      IO.pure(InvalidCredentials())

  override def insertUser(externalId: String, password: String): IO[User] =
    IO.raiseError(new RuntimeException("Not implemented"))

  override def getByExternalId(externalId: String): IO[User] =
    IO.raiseError(new RuntimeException("Not implemented"))

  override def refresh(accessToken: String, refreshToken: String): IO[AuthenticationResponse] =
    tokenManager.refreshAccessToken(refreshToken) match
      case Some((accessToken, refreshToken)) => IO.pure(Authentication(accessToken, refreshToken))
      case None                              => IO.pure(InvalidCredentials())
}

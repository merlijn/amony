package nl.amony.service.auth

import nl.amony.lib.auth.TokenManager
import nl.amony.service.auth.domain.*
import scribe.Logging

import scala.concurrent.Future

class AuthServiceImpl(config: AuthConfig) extends AuthService with Logging {

  private val tokenManager = new TokenManager(config.jwt)

  // https://github.com/Password4j/password4j

  override def authenticate(request: Credentials): Future[AuthenticationResponse] =
    if (request.username == config.adminUsername && request.password == config.adminPassword) {
      val (accessToken, refreshToken) = tokenManager.createAccessAndRefreshTokens(Some(request.username), Set("admin"))
      Future.successful(Authentication(accessToken, refreshToken))
    } else
      Future.successful(InvalidCredentials())

  override def insertUser(request: UpsertUserRequest): Future[User] =
    Future.failed(new RuntimeException("Not implemented"))

  override def getByExternalId(request: GetByExternalId): Future[User] =
    Future.failed(new RuntimeException("Not implemented"))

  override def refresh(request: Authentication): Future[AuthenticationResponse] =
    tokenManager.refreshAccessToken(request.refreshToken) match
      case Some((accessToken, refreshToken)) => Future.successful(Authentication(accessToken, refreshToken))
      case None                              => Future.successful(InvalidCredentials())
}

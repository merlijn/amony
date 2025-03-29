package nl.amony.service.auth

import nl.amony.service.auth.api.*

import scala.concurrent.Future

class AuthServiceImpl(config: AuthConfig) extends AuthServiceGrpc.AuthService {

  private val tokenManager = new TokenManager(config.jwt)
  
  // https://github.com/Password4j/password4j

  override def authenticate(request: Credentials): Future[AuthenticationResponse] =
    if (request.username == config.adminUsername && request.password == config.adminPassword) {
      val (accessToken, refreshToken) = tokenManager.createAccessAndRefreshTokens(Some(request.username), Set("admin"))
      Future.successful(api.Authentication(accessToken, refreshToken))
    } else
      Future.successful(api.InvalidCredentials())

  override def insertUser(request: UpsertUserRequest): Future[api.User] =
    Future.failed(new RuntimeException("Not implemented"))

  override def getByExternalId(request: api.GetByExternalId): Future[api.User] =
    Future.failed(new RuntimeException("Not implemented"))

  override def refresh(request: Authentication): Future[AuthenticationResponse] =
    tokenManager.refreshAccessToken(request.refreshToken) match
      case Some((accessToken, refreshToken)) => Future.successful(api.Authentication(accessToken, refreshToken))
      case None                              => Future.successful(api.InvalidCredentials())
}

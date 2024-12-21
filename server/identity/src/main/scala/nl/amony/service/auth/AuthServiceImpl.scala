package nl.amony.service.auth

import nl.amony.service.auth.api.{AuthServiceGrpc, Credentials, LoginResponse, UpsertUserRequest}

import scala.concurrent.Future

class AuthServiceImpl(config: AuthConfig) extends AuthServiceGrpc.AuthService {

  private val tokenManager = new TokenManager(config.jwt)
  private val adminUserId = "0"

  override def login(request: Credentials): Future[LoginResponse] = {

    if (request.username == config.adminUsername && request.password == config.adminPassword)
      Future.successful(api.Authentication(adminUserId, tokenManager.createJwtToken(adminUserId, Set("admin"))))
    else
      Future.successful(api.InvalidCredentials())
  }

  override def insertUser(request: UpsertUserRequest): Future[api.User] =
    Future.failed(new RuntimeException("Not implemented"))

  override def getByExternalId(request: api.GetByExternalId): Future[api.User] =
    Future.failed(new RuntimeException("Not implemented"))
}

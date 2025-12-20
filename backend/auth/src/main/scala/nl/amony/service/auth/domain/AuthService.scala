package nl.amony.service.auth.domain

import scala.concurrent.Future

trait AuthService:
  def authenticate(request: Credentials): Future[AuthenticationResponse]
  def refresh(request: Authentication): Future[AuthenticationResponse]
  def insertUser(request: UpsertUserRequest): Future[User]
  def getByExternalId(request: GetByExternalId): Future[User]

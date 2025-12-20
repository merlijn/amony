package nl.amony.service.auth.domain

import cats.effect.IO

trait AuthService:
  def authenticate(request: Credentials): IO[AuthenticationResponse]
  def refresh(request: Authentication): IO[AuthenticationResponse]
  def insertUser(request: UpsertUserRequest): IO[User]
  def getByExternalId(request: GetByExternalId): IO[User]

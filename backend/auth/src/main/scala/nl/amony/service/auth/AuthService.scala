package nl.amony.service.auth

import cats.effect.IO

sealed trait AuthenticationResponse

case class Authentication(accessToken: String, refreshToken: String) extends AuthenticationResponse

case class InvalidCredentials() extends AuthenticationResponse

case class User(userId: String, externalId: String, passwordHash: String)

trait AuthService:
  def authenticate(username: String, password: String): IO[AuthenticationResponse]
  def refresh(accessToken: String, refreshToken: String): IO[AuthenticationResponse]

package nl.amony.service.auth

import cats.effect.IO

sealed trait AuthenticationResponse

case class Authentication(accessToken: String, refreshToken: String) extends AuthenticationResponse

sealed trait AuthenticationError

case object InvalidCredentials   extends AuthenticationError
case object UnknownOAuthProvider extends AuthenticationError

case class User(userId: String, externalId: String, passwordHash: String)

case class OauthToken(provider: String, token: String)

trait AuthService:
  def oauthProviders: Map[String, OauthProvider]
  def authenticate(oauthToken: OauthToken): IO[Either[AuthenticationError, Authentication]]
  def authenticate(username: String, password: String): IO[Either[AuthenticationError, Authentication]]
  def refresh(accessToken: String, refreshToken: String): IO[Either[AuthenticationError, Authentication]]

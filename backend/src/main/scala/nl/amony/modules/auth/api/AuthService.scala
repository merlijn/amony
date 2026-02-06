package nl.amony.modules.auth.api

import java.util.UUID

import cats.data.EitherT
import cats.effect.IO
import scribe.Logging
import sttp.client4.Backend
import sttp.client4.circe.asJson

import nl.amony.modules.*
import nl.amony.modules.auth.*

sealed trait AuthenticationError

case object InvalidCredentials   extends AuthenticationError
case object UnknownOAuthProvider extends AuthenticationError
case object UnknownError         extends AuthenticationError

case class OauthTokenCredentials(provider: String, token: String)

case class OauthTokenResponse(
  access_token: String,
  token_type: String,
  expires_in: Int,
  refresh_token: Option[String],
  scope: Option[String]
) derives io.circe.Codec

case class UserInfo(
  sub: String,           // guaranteed - unique user identifier
  email: Option[String], // optional - may require 'email' scope
  email_verified: Option[Boolean],
  name: Option[String],
  given_name: Option[String],
  family_name: Option[String],
  picture: Option[String],
  locale: Option[String]
) derives io.circe.Codec

class AuthService(config: AuthConfig, httpClient: Backend[IO]) extends Logging {

  private val tokenManager = new TokenManager(config.jwt)

  val oauthProviders: Map[String, OauthProvider] = config.oauthProviders.map(p => p.name -> p).toMap

  private def getToken(provider: OauthProvider, code: String): EitherT[IO, AuthenticationError, OauthTokenResponse] = {

    val redirectUri = config.publicUri.addPath("api", "auth", "callback", provider.name)

    val body = Map(
      "grant_type"    -> "authorization_code",
      "code"          -> code,
      "client_id"     -> provider.clientId,
      "client_secret" -> provider.clientSecret,
      "redirect_uri"  -> redirectUri.toString
    )

    val req = sttp.client4.basicRequest
      .post(provider.tokenUrl)
      .body(body)
      .response(asJson[OauthTokenResponse])

    EitherT(httpClient.send(req).map(_.body.left.map(_.getMessage))).leftMap { error =>
      logger.error(s"Error fetching token from OAuth provider $provider: $error")
      UnknownOAuthProvider
    }
  }

  private def getUserInfo(provider: OauthProvider, accessToken: String): IO[Either[String, UserInfo]] = {
    val req = sttp.client4.basicRequest
      .get(provider.userInfoUrl)
      .header("Authorization", s"Bearer $accessToken")
      .response(asJson[UserInfo])

    httpClient.send(req).map(_.body.left.map(_.getMessage))
  }

  def authenticate(oauthToken: OauthTokenCredentials): IO[Either[AuthenticationError, Authentication]] = {

    val result =
      for
        provider      <- EitherT.fromOption[IO](oauthProviders.get(oauthToken.provider), UnknownOAuthProvider: AuthenticationError)
        tokenResponse <- getToken(provider, oauthToken.token)
      yield tokenManager.createAccessAndRefreshTokens(Some(UUID.randomUUID().toString), roles = provider.defaultRoles)

    result.value
  }

  def refresh(refreshToken: String): IO[Either[AuthenticationError, Authentication]] =
    tokenManager.refreshAccessToken(refreshToken) match
      case Some(authentication) => IO.pure(Right(authentication))
      case None                 => IO.pure(Left(InvalidCredentials))
}

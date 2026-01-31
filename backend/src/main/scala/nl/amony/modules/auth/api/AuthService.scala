package nl.amony.modules.auth.api

import java.util.UUID

import cats.effect.IO
import scribe.Logging
import sttp.client4.Backend
import sttp.client4.circe.asJson

import nl.amony.modules.*
import nl.amony.modules.auth.*

sealed trait AuthenticationResponse

case class Authentication(accessToken: String, refreshToken: String) extends AuthenticationResponse

sealed trait AuthenticationError

case object InvalidCredentials   extends AuthenticationError
case object UnknownOAuthProvider extends AuthenticationError

case class OauthTokenCredentials(provider: String, token: String)

case class OauthTokenResponse(
  access_token: String,
  token_type: String,
  expires_in: Int,
  refresh_token: Option[String],
  scope: Option[String]
) derives io.circe.Codec

class AuthService(config: AuthConfig, httpClient: Backend[IO]) extends Logging {

  private val tokenManager = new TokenManager(config.jwt)

  val oauthProviders: Map[String, OauthProvider] = config.oauthProviders.map(p => p.name -> p).toMap

  private def getToken(provider: OauthProvider, code: String): IO[Either[String, OauthTokenResponse]] = {

    val redirectUri = config.publicUri.addPath("api", "auth", "callback", provider.name)

    val body = Map(
      "grant_type"    -> "authorization_code",
      "code"          -> code,
      "client_id"     -> provider.clientId,
      "client_secret" -> provider.clientSecret,
      "redirect_uri"  -> redirectUri.toString
    )

    val req = sttp.client4.basicRequest
      .post(provider.tokenUri)
      .body(body)
      .response(asJson[OauthTokenResponse])

    httpClient.send(req).map(_.body.left.map(_.getMessage))
  }

  def authenticate(oauthToken: OauthTokenCredentials): IO[Either[AuthenticationError, Authentication]] = {

    oauthProviders.get(oauthToken.provider) match
      case None           =>
        logger.error(s"Unknown OAuth provider: ${oauthToken.provider}")
        IO.pure(Left(UnknownOAuthProvider))
      case Some(provider) =>
        getToken(provider, oauthToken.token).flatMap:
          case Left(error)          =>
            logger.error(s"Error fetching token from OAuth provider ${oauthToken.provider}: $error")
            IO.raiseError(new RuntimeException(s"Error fetching token from OAuth provider ${oauthToken.provider}: $error"))
          case Right(tokenResponse) =>
            val (accessToken, refreshToken) =
              tokenManager.createAccessAndRefreshTokens(Some(UUID.randomUUID().toString), roles = provider.defaultRoles)
            IO.pure(Right(Authentication(accessToken, refreshToken)))
  }

  def refresh(accessToken: String, refreshToken: String): IO[Either[AuthenticationError, Authentication]] =
    tokenManager.refreshAccessToken(refreshToken) match
      case Some((accessToken, refreshToken)) => IO.pure(Right(Authentication(accessToken, refreshToken)))
      case None                              => IO.pure(Left(InvalidCredentials))
}

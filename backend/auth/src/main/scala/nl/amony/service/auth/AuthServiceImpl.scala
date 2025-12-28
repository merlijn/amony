package nl.amony.service.auth

import cats.effect.IO
import scribe.Logging
import sttp.client4.Backend
import sttp.client4.circe.asJson

import nl.amony.lib.auth.TokenManager

case class OauthTokenResponse(
  access_token: String,
  token_type: String,
  expires_in: Int,
  refresh_token: Option[String],
  scope: Option[String]
) derives io.circe.Codec

class AuthServiceImpl(config: AuthConfig, httpClient: Backend[IO]) extends AuthService with Logging {

  private val tokenManager = new TokenManager(config.jwt)

  override val oauthProviders: Map[String, OauthProvider] = config.oauthProviders.map(p => p.name -> p).toMap

  private def getToken(provider: OauthProvider, code: String): IO[Either[String, OauthTokenResponse]] = {

    val redirectUri = config.publicUri.addPath("api", "oauth", "callback", provider.name)
    val req         = sttp.client4.basicRequest.post(provider.host.addPath(provider.tokenEndpoint))
    val body        = Map(
      "grant_type"    -> "authorization_code",
      "code"          -> code,
      "client_id"     -> provider.clientId,
      "client_secret" -> provider.clientSecret,
      "redirect_uri"  -> redirectUri.toString
    )

    req.body(body).response(asJson[OauthTokenResponse]).send(httpClient).map(_.body.left.map(_.getMessage))
  }

  override def authenticate(username: String, password: String): IO[Either[AuthenticationError, Authentication]] =
    if username == config.adminUsername && password == config.adminPassword then {
      val (accessToken, refreshToken) = tokenManager.createAccessAndRefreshTokens(Some(username), Set("admin"))
      IO.pure(Right(Authentication(accessToken, refreshToken)))
    } else IO.pure(Left(InvalidCredentials))

  override def authenticate(oauthToken: OauthToken): IO[Either[AuthenticationError, Authentication]] = {

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
            val (accessToken, refreshToken) = tokenManager.createAccessAndRefreshTokens(None, Set(oauthToken.provider))
            IO.pure(Right(Authentication(accessToken, refreshToken)))
  }

  override def refresh(accessToken: String, refreshToken: String): IO[Either[AuthenticationError, Authentication]] =
    tokenManager.refreshAccessToken(refreshToken) match
      case Some((accessToken, refreshToken)) => IO.pure(Right(Authentication(accessToken, refreshToken)))
      case None                              => IO.pure(Left(InvalidCredentials))
}

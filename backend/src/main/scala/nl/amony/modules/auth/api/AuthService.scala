package nl.amony.modules.auth.api

import java.time.Instant
import java.util.UUID

import cats.data.EitherT
import cats.effect.IO
import scribe.Logging
import sttp.client4.Backend
import sttp.client4.circe.asJson

import nl.amony.modules.*
import nl.amony.modules.auth.*
import nl.amony.modules.auth.dal.{UserDatabase, UserRow}

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

class AuthService(config: AuthConfig, httpClient: Backend[IO], userDatabase: UserDatabase) extends Logging {

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

  private def getUserInfo(provider: OauthProvider, accessToken: String): EitherT[IO, AuthenticationError, UserInfo] = {
    val req = sttp.client4.basicRequest
      .get(provider.userInfoUrl)
      .header("Authorization", s"Bearer $accessToken")
      .response(asJson[UserInfo])

    EitherT(httpClient.send(req).map(_.body.left.map(_.getMessage))).leftMap { error =>
      logger.error(s"Error fetching user info from OAuth provider $provider: $error")
      UnknownError
    }
  }

  private def getOrInsertUser(provider: OauthProvider, userInfo: UserInfo, email: String): IO[User] = {
    userDatabase.getByEmail(email).flatMap {
      case Some(userRow) => IO.pure(userRow.toUser)
      case None          =>
        val newUser = User(
          id             = UserId(UUID.randomUUID().toString),
          email          = email,
          authProvider   = provider.name,
          authSubject    = userInfo.sub,
          timeRegistered = Instant.now,
          roles          = provider.defaultRoles
        )
        userDatabase.insert(UserRow.fromUser(newUser)).map(_ => newUser)
    }
  }

  def authenticate(oauthToken: OauthTokenCredentials): EitherT[IO, AuthenticationError, Authentication] =
    for
      provider      <- EitherT.fromOption[IO](oauthProviders.get(oauthToken.provider), UnknownOAuthProvider: AuthenticationError)
      tokenResponse <- getToken(provider, oauthToken.token)
      userInfo      <- getUserInfo(provider, tokenResponse.access_token)
      email         <- EitherT.fromOption[IO](userInfo.email, UnknownError: AuthenticationError)
      user          <- EitherT.liftF(getOrInsertUser(provider, userInfo, email))
    yield tokenManager.createAccessAndRefreshTokens(Some(user.id), roles = user.roles)

  def refresh(refreshToken: String): IO[Either[AuthenticationError, Authentication]] =
    tokenManager.refreshAccessToken(refreshToken) match
      case Some(authentication) => IO.pure(Right(authentication))
      case None                 => IO.pure(Left(InvalidCredentials))
}

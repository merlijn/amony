package nl.amony.modules.auth.api

import java.time.{Instant, OffsetDateTime, ZoneOffset}
import java.util.UUID
import scala.jdk.DurationConverters.*
import scala.util.{Random, Try}

import cats.data.EitherT
import cats.effect.IO
import scribe.Logging
import sttp.client4.Backend
import sttp.client4.circe.asJson

import nl.amony.lib.hash.Base32
import nl.amony.modules.*
import nl.amony.modules.auth.*
import nl.amony.modules.auth.dal.{OAuthStateDatabase, OAuthStateRow, UserDatabase, UserRow}

sealed trait AuthenticationError

case object InvalidCredentials      extends AuthenticationError
case object UnknownIdentityProvider extends AuthenticationError
case object UnknownError            extends AuthenticationError

case class OauthTokenResponse(
  access_token: String,
  token_type: String,
  expires_in: Int,
  refresh_token: Option[String],
  scope: Option[String],
  id_token: Option[String] = None
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

class FederatedLoginService(config: AuthConfig, httpClient: Backend[IO], userDatabase: UserDatabase, oauthStateDatabase: OAuthStateDatabase) extends Logging {

  private val tokenManager = new TokenManager(config.jwt)

  val identityProviders: Map[String, IdentityProvider] = config.identityProviders.map(p => p.name -> p).toMap

  private def nowUTC = OffsetDateTime.now(ZoneOffset.UTC)

  def createState(provider: String): IO[String] = {
    val stateId = config.random.nextLong

    val stateRow = OAuthStateRow(
      id         = stateId,
      provider   = provider,
      created_at = nowUTC
    )
    oauthStateDatabase.insert(stateRow).map(_ => java.lang.Long.toUnsignedString(stateId, 16))
  }

  private def validateAndConsumeState(provider: String, state: String): EitherT[IO, AuthenticationError, Unit] =
    for
      stateId  <- EitherT.fromOption[IO](Try(java.lang.Long.parseUnsignedLong(state, 16)).toOption, InvalidCredentials)
      stateRow <- EitherT.fromOptionF(oauthStateDatabase.getById(stateId), InvalidCredentials)
      _        <- EitherT.liftF(oauthStateDatabase.delete(stateId))
      isValid   = stateRow.provider == provider && nowUTC.isBefore(stateRow.created_at.plus(config.oauthStateExpiration.toJava))
      _        <- EitherT.cond[IO](isValid, (), InvalidCredentials)
    yield ()

  private def getToken(provider: IdentityProvider, code: String, origin: RequestOrigin): EitherT[IO, AuthenticationError, OauthTokenResponse] = {

    val redirectUri = origin.callbackUri(provider.name)

    val body = Map(
      "grant_type"    -> "authorization_code",
      "code"          -> code,
      "client_id"     -> provider.clientId,
      "client_secret" -> provider.clientSecret,
      "redirect_uri"  -> redirectUri
    )

    val req = sttp.client4.basicRequest
      .post(provider.tokenUrl)
      .body(body)
      .response(asJson[OauthTokenResponse])

    EitherT(httpClient.send(req).map(_.body.left.map(_.getMessage))).leftMap { error =>
      logger.error(s"Error fetching token from identity provider $provider: $error")
      UnknownError
    }
  }

  private def getUserInfo(provider: IdentityProvider, accessToken: String): EitherT[IO, AuthenticationError, UserInfo] = {
    val req = sttp.client4.basicRequest
      .get(provider.userInfoUrl)
      .header("Authorization", s"Bearer $accessToken")
      .response(asJson[UserInfo])

    EitherT(httpClient.send(req).map(_.body)).leftMap { error =>
      logger.error(s"Error fetching user info from identity provider $provider", error)
      UnknownError
    }
  }

  private def getOrInsertUser(provider: IdentityProvider, userInfo: UserInfo, email: String): IO[User] = {
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

  /**
   * Completes the identity provider callback: validates and consumes the login state, exchanges the
   * authorization code for a token, resolves the user and issues a local session.
   */
  def login(provider: String, code: String, state: String, origin: RequestOrigin): EitherT[IO, AuthenticationError, Authentication] =
    for
      _              <- validateAndConsumeState(provider, state)
      providerConfig <- EitherT.fromOption[IO](identityProviders.get(provider), UnknownIdentityProvider: AuthenticationError)
      tokenResponse  <- getToken(providerConfig, code, origin)
      userInfo       <- getUserInfo(providerConfig, tokenResponse.access_token)
      email          <- EitherT.fromOption[IO](userInfo.email, UnknownError: AuthenticationError)
      user           <- EitherT.liftF(getOrInsertUser(providerConfig, userInfo, email))
    yield tokenManager
      .createAccessAndRefreshTokens(Some(user.id), roles = user.roles)
      .copy(providerIdToken = providerIdTokenFor(providerConfig, tokenResponse))

  /** The provider ID token is only retained when it will be needed as `id_token_hint` at logout. */
  private def providerIdTokenFor(providerConfig: IdentityProvider, tokenResponse: OauthTokenResponse): Option[ProviderIdToken] =
    for
      _       <- providerConfig.endSessionUrl
      idToken <- tokenResponse.id_token
    yield ProviderIdToken(providerConfig.name, idToken)

  /** The provider's end-session URL (RP-Initiated Logout) when it has one configured. */
  def logoutUrl(providerIdToken: ProviderIdToken, origin: RequestOrigin): Option[String] =
    for
      provider      <- identityProviders.get(providerIdToken.provider)
      endSessionUrl <- provider.endSessionUrl
    yield endSessionUrl.addParams(Map(
      "id_token_hint"            -> providerIdToken.idToken,
      "client_id"                -> provider.clientId,
      "post_logout_redirect_uri" -> origin.rootUri
    )).toString

  def refresh(refreshToken: String): IO[Either[AuthenticationError, Authentication]] =
    tokenManager.refreshAuthentication(refreshToken) match
      case Some(authentication) => IO.pure(Right(authentication))
      case None                 => IO.pure(Left(InvalidCredentials))
}

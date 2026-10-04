package nl.amony.modules.auth.http

import scala.concurrent.duration.*

import cats.data.EitherT
import cats.effect.IO
import io.circe.parser.decode
import org.mockito.IdiomaticMockito.returns
import org.mockito.Mockito.RETURNS_DEFAULTS
import org.mockito.scalatest.MockitoSugar
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import sttp.model.{StatusCode, Uri}

import nl.amony.lib.tapir.dsl.error.ErrorBody
import nl.amony.lib.tapir.test.EndpointFixture
import nl.amony.modules.auth.api.*
import nl.amony.modules.auth.{AuthConfig, HS256Config, IdentityProvider, JwtConfig, RoleAccessConfig}

/**
 * Tapir-layer tests for the auth endpoints: the domain service is mocked so each test only verifies
 * that the endpoint translates between the request/response and the domain logic. No HTTP server
 * (or any other framework) is involved.
 */
class AuthRoutesSpec extends AnyWordSpecLike with Matchers with MockitoSugar {

  private val authConfig = AuthConfig(
    enabled           = true,
    requireLogin      = true,
    jwt               = JwtConfig(5.minutes, 1.hour, HS256Config("test-secret-key")),
    secureCookies     = false,
    identityProviders = Nil,
    accessControl     = Map(
      Role.Anonymous     -> RoleAccessConfig(Set.empty),
      Role.Authenticated -> RoleAccessConfig(Set.empty)
    )
  )

  private given ApiSecurity = new ApiSecurity(authConfig)

  // RETURNS_DEFAULTS instead of mockito-scala's smart-nulls: the latter tries to mock cats.effect.IO
  // for the (unstubbed) IO-returning methods, which fails during IO's class initialisation.
  private val loginServiceMock = mock[FederatedLoginService](RETURNS_DEFAULTS)
  private val authRoutes       = AuthRoutes.apply(loginServiceMock, authConfig)

  private val testState = "test-state-123"

  private val provider = IdentityProvider(
    name         = "test-provider",
    clientId     = "test-client-id",
    clientSecret = "test-secret",
    authorizeUrl = Uri.unsafeParse("https://idp.example.com/authorize"),
    tokenUrl     = Uri.unsafeParse("https://idp.example.com/token"),
    userInfoUrl  = Uri.unsafeParse("https://idp.example.com/userinfo")
  )

  loginServiceMock.identityProviders returns Map(provider.name -> provider)
  loginServiceMock.createState(any[String]) returns IO.pure(testState)

  "AuthRoutes" when {

    "processing login requests" should {

      "redirect to the identity provider with the expected parameters" in new EndpointFixture(authRoutes, AuthRoutes.loginEndpoint) {
        val response = request(path = "/api/auth/login/test-provider").sendUnsafeSync()

        response.code shouldBe StatusCode.Found

        val location = response.header("Location").getOrElse(fail("missing Location header"))
        val params   = Uri.unsafeParse(location).paramsMap

        params.get("client_id") shouldBe Some("test-client-id")
        params.get("response_type") shouldBe Some("code")
        params.get("redirect_uri") shouldBe Some("https://app.example.com/api/auth/callback/test-provider")
        params.get("scope") shouldBe Some("openid profile email")
        params.get("state") shouldBe Some(testState)
      }

      "set the oauth_login_state cookie" in new EndpointFixture(authRoutes, AuthRoutes.loginEndpoint) {
        val response  = request(path = "/api/auth/login/test-provider").sendUnsafeSync()
        val setCookie = response.header("Set-Cookie").getOrElse(fail("missing Set-Cookie header"))

        setCookie should include(s"oauth_login_state=$testState")
      }

      "return 404 with an error body for an unknown identity provider" in new EndpointFixture(authRoutes, AuthRoutes.loginEndpoint) {
        val response = request(path = "/api/auth/login/does-not-exist").sendUnsafeSync()

        response.code shouldBe StatusCode.NotFound
        decode[ErrorBody](response.body.merge) shouldBe Right(ErrorBody("not_found", "Resource not found"))
      }
    }

    "processing callback requests" should {

      "return 403 with an error body when the identity provider shares no email" in new EndpointFixture(authRoutes, AuthRoutes.callbackEndpoint) {
        loginServiceMock.login(any[String], any[String], any[String], any[RequestOrigin]) returns
          EitherT(IO.pure(Left(MissingEmail): Either[AuthenticationError, Authentication]))

        val response = request(path = "/api/auth/callback/test-provider", queryParams = Map("code" -> "the-code", "state" -> testState))
          .cookie("oauth_login_state", testState)
          .sendUnsafeSync()

        response.code shouldBe StatusCode.Forbidden
        decode[ErrorBody](response.body.merge) shouldBe Right(ErrorBody("forbidden", "You do not have permission to perform this action"))
      }

      "return 502 with an error body when the identity provider returns an unusable response" in new EndpointFixture(
        authRoutes,
        AuthRoutes.callbackEndpoint
      ) {
        loginServiceMock.login(any[String], any[String], any[String], any[RequestOrigin]) returns
          EitherT(IO.pure(Left(IdentityProviderFailure): Either[AuthenticationError, Authentication]))

        val response = request(path = "/api/auth/callback/test-provider", queryParams = Map("code" -> "the-code", "state" -> testState))
          .cookie("oauth_login_state", testState)
          .sendUnsafeSync()

        response.code shouldBe StatusCode.BadGateway
        decode[ErrorBody](response.body.merge) shouldBe Right(ErrorBody("bad_gateway", "The identity provider returned an invalid response"))
      }
    }

    "processing session requests" should {

      val userToken =
        new TokenManager(authConfig.jwt).createAccessAndRefreshTokens(Some("user-1"), Set(Role.Authenticated)).accessToken

      "return the current session for a valid access token" in new EndpointFixture(authRoutes, AuthRoutes.sessionEndpoint) {
        val response = request(path = "/api/auth/session").cookie("access_token", userToken).sendUnsafeSync()

        response.code shouldBe StatusCode.Ok
        decode[AuthToken](response.body.getOrElse(fail("expected a response body"))) shouldBe
          Right(AuthToken(UserId("user-1"), Set(Role.Authenticated)))
      }

      "reject requests without an access token" in new EndpointFixture(authRoutes, AuthRoutes.sessionEndpoint) {
        val response = request(path = "/api/auth/session").sendUnsafeSync()

        response.code shouldBe StatusCode.Unauthorized
        decode[ErrorBody](response.body.merge) shouldBe Right(ErrorBody("unauthorized", "Authentication is required"))
      }
    }
  }
}

package nl.amony.modules.auth.http

import scala.concurrent.duration.*

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.parser.decode
import org.mockito.IdiomaticMockito.returns
import org.mockito.Mockito.RETURNS_DEFAULTS
import org.mockito.scalatest.MockitoSugar
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import sttp.capabilities.fs2.Fs2Streams
import sttp.client3.testing.SttpBackendStub
import sttp.client3.{SttpBackend, basicRequest}
import sttp.client4.impl.cats.CatsMonadError
import sttp.model.{StatusCode, Uri}
import sttp.monad.MonadError
import sttp.tapir.AnyEndpoint
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.stub.TapirStubInterpreter

import nl.amony.lib.tapir.dsl.ServerEndpoints
import nl.amony.modules.auth.api.*
import nl.amony.modules.auth.{AuthConfig, HS256Config, IdentityProvider, JwtConfig, RoleAccessConfig}

/**
 * Tapir-layer tests for the auth endpoints: the domain service is mocked so each test only verifies
 * that the endpoint translates between the request/response and the domain logic. No HTTP server
 * (or any other framework) is involved.
 */
class AuthRoutesSpec extends AnyWordSpecLike with Matchers with MockitoSugar {

  private given MonadError[IO] = new CatsMonadError[IO]

  private val authConfig = AuthConfig(
    enabled           = true,
    requireLogin      = true,
    jwt               = JwtConfig(5.minutes, 1.hour, HS256Config("test-secret-key")),
    secureCookies     = false,
    identityProviders = Nil,
    accessControl     = Map(
      Role.Anonymous     -> RoleAccessConfig(Set.empty, Set.empty, Set.empty),
      Role.Authenticated -> RoleAccessConfig(Set.empty, Set.empty, Set.empty)
    )
  )

  private given ApiSecurity = new ApiSecurity(authConfig)

  // RETURNS_DEFAULTS instead of mockito-scala's smart-nulls: the latter tries to mock cats.effect.IO
  // for the (unstubbed) IO-returning methods, which fails during IO's class initialisation.
  private val loginServiceMock = mock[FederatedLoginService](RETURNS_DEFAULTS)

  private def findEndpoint(definition: AnyEndpoint)(endpoints: ServerEndpoints[IO]): ServerEndpoint[Fs2Streams[IO], IO] =
    val name = definition.info.name.getOrElse(fail("endpoint definition has no name"))
    endpoints.find(_.endpoint.info.name.contains(name)).getOrElse(fail(s"$name endpoint not found"))

  private def stubBackend(endpoint: ServerEndpoint[Fs2Streams[IO], IO]): SttpBackend[IO, Fs2Streams[IO]] =
    TapirStubInterpreter(SttpBackendStub[IO, Fs2Streams[IO]](summon[MonadError[IO]]))
      .whenServerEndpointRunLogic(endpoint)
      .backend()

  private val authRoutes = AuthRoutes.apply(loginServiceMock, authConfig)

  "AuthRoutes" when {

    "processing login requests" should {

      val testState = "test-state-123"

      val provider = IdentityProvider(
        name         = "test-provider",
        clientId     = "test-client-id",
        clientSecret = "test-secret",
        authorizeUrl = Uri.unsafeParse("https://idp.example.com/authorize"),
        tokenUrl     = Uri.unsafeParse("https://idp.example.com/token"),
        userInfoUrl  = Uri.unsafeParse("https://idp.example.com/userinfo")
      )

      loginServiceMock.identityProviders returns Map(provider.name -> provider)
      loginServiceMock.createState(any[String]) returns IO.pure(testState)

      val loginEndpoint = findEndpoint(AuthRoutes.loginEndpoint)(authRoutes)

      def login(provider: String) =
        basicRequest
          .get(Uri.unsafeParse(s"http://localhost/api/auth/login/$provider"))
          .header("X-Forwarded-Host", "app.example.com")
          .header("X-Forwarded-Proto", "https")
          .send(stubBackend(loginEndpoint))
          .unsafeRunSync()

      "redirect to the identity provider with the expected parameters" in {
        val response = login("test-provider")

        response.code shouldBe StatusCode.Found

        val location = response.header("Location").getOrElse(fail("missing Location header"))
        val params   = Uri.unsafeParse(location).paramsMap

        params.get("client_id") shouldBe Some("test-client-id")
        params.get("response_type") shouldBe Some("code")
        params.get("redirect_uri") shouldBe Some("https://app.example.com/api/auth/callback/test-provider")
        params.get("scope") shouldBe Some("openid profile email")
        params.get("state") shouldBe Some(testState)
      }

      "set the oauth_login_state cookie" in {
        val response  = login("test-provider")
        val setCookie = response.header("Set-Cookie").getOrElse(fail("missing Set-Cookie header"))

        setCookie should include(s"oauth_login_state=$testState")
      }

      "return 404 for an unknown identity provider" in {
        login("does-not-exist").code shouldBe StatusCode.NotFound
      }
    }

    "processing session requests" should {

      val sessionEndpoint = findEndpoint(AuthRoutes.sessionEndpoint)(authRoutes)

      val userToken =
        new TokenManager(authConfig.jwt).createAccessAndRefreshTokens(Some("user-1"), Set(Role.Authenticated)).accessToken

      def session(accessToken: Option[String]) =
        val request = basicRequest
          .get(Uri.unsafeParse("http://localhost/api/auth/session"))
          .header("X-Forwarded-Host", "app.example.com")
          .header("X-Forwarded-Proto", "https")

        accessToken.fold(request)(token => request.cookie("access_token", token))
          .send(stubBackend(sessionEndpoint))
          .unsafeRunSync()

      "return the current session for a valid access token" in {
        val response = session(Some(userToken))

        response.code shouldBe StatusCode.Ok
        decode[AuthToken](response.body.getOrElse(fail("expected a response body"))) shouldBe
          Right(AuthToken(UserId("user-1"), Set(Role.Authenticated)))
      }

      "reject requests without an access token" in {
        session(None).code shouldBe StatusCode.Unauthorized
      }
    }
  }
}

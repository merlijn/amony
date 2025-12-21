package nl.amony.service.auth

import java.time.{Duration, Instant}
import java.util.UUID

import cats.effect.IO
import cats.implicits.toSemigroupKOps
import org.http4s.HttpRoutes
import org.http4s.UrlForm.given
import org.http4s.dsl.io.*
import scribe.Logging
import sttp.model.headers.CookieValueWithMeta
import sttp.model.{HeaderNames, StatusCode}
import sttp.tapir.*
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.server.http4s.{Http4sServerInterpreter, Http4sServerOptions}

import nl.amony.lib.auth.{ApiSecurity, AuthToken, SecurityError, SecurityInput, securityInput}
import nl.amony.service.auth.domain.{AuthService, Authentication, InvalidCredentials}

case class LoginCredentials(username: String, password: String) derives Schema

case class RedirectResponse(location: String)
case class AuthCookies(accessToken: CookieValueWithMeta, refreshToken: CookieValueWithMeta, xsrfToken: CookieValueWithMeta)

object AuthRoutes extends Logging {

  val errorOutput = {
    val unauthorizedOutput = oneOfVariantSingletonMatcher(statusCode(StatusCode.Unauthorized))(SecurityError.Unauthorized)
    val forbiddenOutput    = oneOfVariantSingletonMatcher(statusCode(StatusCode.Forbidden))(SecurityError.Forbidden)

    oneOf[SecurityError](unauthorizedOutput, forbiddenOutput)
  }

  private val cookieOutput = (setCookie("access_token") and setCookie("refresh_token") and setCookie("XSRF-TOKEN")).mapTo[AuthCookies]

  private val redirectOut = (statusCode(StatusCode.Found) and header[String](HeaderNames.Location)).mapTo[RedirectResponse]

  val sessionEndpoint: Endpoint[SecurityInput, Unit, SecurityError, AuthToken, Any] = endpoint.name("getSession").tag("auth")
    .description("Get the current session").get.in("api" / "auth" / "session").securityIn(securityInput).out(jsonBody[AuthToken])
    .errorOut(errorOutput)

  val loginEndpoint = endpoint.name("authLogin").tag("auth").description("Login the current user").post.in("api" / "auth" / "login")
    .in(formBody[LoginCredentials]: EndpointIO.Body[String, LoginCredentials]).out(redirectOut and cookieOutput).errorOut(errorOutput)

  val refreshEndpoint = endpoint.name("authRefreshTokens").tag("auth").description("Refresh the users auth tokens").post
    .in("api" / "auth" / "refresh").in(cookie[String]("refresh_token")).out(cookieOutput).errorOut(errorOutput)

  val logoutEndpoint: Endpoint[Unit, Unit, Unit, AuthCookies, Any] = endpoint.name("authLogout").tag("auth").description("Logout the current user")
    .post.in("api" / "auth" / "logout").out(statusCode(StatusCode.Ok)).out(cookieOutput)

  val endpoints = List(loginEndpoint, refreshEndpoint, sessionEndpoint, logoutEndpoint)

  def apply(authService: AuthService, authConfig: AuthConfig, apiSecurity: ApiSecurity)(
    using serverOptions: Http4sServerOptions[IO]
  ): HttpRoutes[IO] = {

    def createCookies(apiAuthentication: Authentication): AuthCookies = {
      val accessTokenCookie = CookieValueWithMeta.unsafeApply(
        value    = apiAuthentication.accessToken,
        path     = Some("/"),
        httpOnly = true,
        secure   = authConfig.secureCookies,
        expires  = Some(Instant.now().plus(Duration.ofSeconds(authConfig.jwt.accessTokenExpiration.toSeconds)))
      )

      val refreshCookie = CookieValueWithMeta.unsafeApply(
        value    = apiAuthentication.refreshToken,
        path     = Some("/"),
        httpOnly = true,
        secure   = authConfig.secureCookies,
        expires  = Some(Instant.now().plus(Duration.ofSeconds(authConfig.jwt.refreshTokenExpiration.toSeconds)))
      )

      val xsrfCookie = CookieValueWithMeta
        .unsafeApply(value = UUID.randomUUID().toString, path = Some("/"), httpOnly = false, secure = authConfig.secureCookies)

      AuthCookies(accessTokenCookie, refreshCookie, xsrfCookie)
    }

    val sessionImpl = sessionEndpoint.serverSecurityLogicPure(apiSecurity.requireSession).serverLogic(auth => _ => IO(Right(auth)))

    val loginImpl = loginEndpoint.serverLogic: req =>
      authService.authenticate(req.username, req.password).flatMap:
        case InvalidCredentials()           => IO(Left(SecurityError.Unauthorized))
        case authentication: Authentication => IO(Right(RedirectResponse("/") -> createCookies(authentication)))

    val refreshImpl = refreshEndpoint.serverLogic {
      refreshToken =>
        authService.refresh("", refreshToken).flatMap:
          case InvalidCredentials()           => IO(Left(SecurityError.Unauthorized))
          case authentication: Authentication => IO(Right(createCookies(authentication)))
    }

    val logoutImpl = logoutEndpoint.serverLogic {
      _ =>

        val expiredEmptyCookie = CookieValueWithMeta
          .unsafeApply("", path = Some("/"), httpOnly = true, secure = authConfig.secureCookies, expires = Some(Instant.ofEpochSecond(0L)))

        IO(Right(AuthCookies(expiredEmptyCookie, expiredEmptyCookie, expiredEmptyCookie)))
    }

    Http4sServerInterpreter[IO](serverOptions).toRoutes(List(loginImpl, refreshImpl, sessionImpl, logoutImpl)) <+> loginPage(authService, authConfig)
  }

  private def loginPage(authService: AuthService, authConfig: AuthConfig) = HttpRoutes.of[IO]:
    case GET -> Root / "login" => Ok(fs2.io.readClassLoaderResource[IO]("login.html"))
}

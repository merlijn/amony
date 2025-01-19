package nl.amony.service.auth

import cats.effect.IO
import nl.amony.service.auth.api.AuthServiceGrpc.AuthService
import nl.amony.service.auth.tapir.{SecurityInput, securityInput}
import org.http4s.UrlForm.given
import org.http4s.dsl.io.*
import org.http4s.headers.{Location, `WWW-Authenticate`}
import org.http4s.{Challenge, Header, HttpDate, HttpRoutes, ResponseCookie, Uri, UrlForm}
import scribe.Logging
import sttp.model.StatusCode
import sttp.model.headers.CookieValueWithMeta
import sttp.tapir.*
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.server.http4s.Http4sServerInterpreter

import java.time.Instant
import java.util.UUID

object AuthRoutes extends Logging {

  private val unauthorizedResponse = Unauthorized(`WWW-Authenticate`(Challenge("Bearer", "api")))

  val unauthorizedOutput = oneOfVariantSingletonMatcher(statusCode(StatusCode.Unauthorized))(SecurityError.Unauthorized)
  val forbiddenOutput    = oneOfVariantSingletonMatcher(statusCode(StatusCode.Forbidden))(SecurityError.Forbidden)
  
  val errorOutput = oneOf[SecurityError](unauthorizedOutput, forbiddenOutput)
  
  val session: Endpoint[SecurityInput, Unit, SecurityError, AuthToken, Any] =
    endpoint
      .name("getSession")
      .tag("auth")
      .description("Get the current session")
      .get.in("api" / "auth" / "session")
      .securityIn(securityInput)
      .out(jsonBody[AuthToken])
      .errorOut(errorOutput)
  
  val logout: Endpoint[Unit, Unit, Unit, (CookieValueWithMeta, CookieValueWithMeta, CookieValueWithMeta), Any] =
    endpoint
      .name("authLogout")
      .tag("auth")
      .description("Logout the current user")
      .post.in("api" / "auth" / "logout")
      .out(statusCode(StatusCode.Ok))
      .out(setCookie("access_token"))
      .out(setCookie("refresh_token"))
      .out(setCookie("XSRF-TOKEN"))
    
  val endpoints = List(session, logout)
  
  def routes(authService: AuthService, authConfig: AuthConfig, jwtDecoder: JwtDecoder): HttpRoutes[IO] = {
    
    val authenticator = Authenticator(jwtDecoder)
    
    val sessionImpl = session
      .serverSecurityLogicPure(authenticator.requireSession)
      .serverLogic(auth => _ => IO(Right(auth)))

    val logoutImpl = logout
      .serverLogic(_ => {

        val expiredEmptyCookie = CookieValueWithMeta.unsafeApply("", path = Some("/"), httpOnly = true, secure = authConfig.secureCookies, expires = Some(Instant.ofEpochSecond(0L)))
        
        IO(Right((expiredEmptyCookie, expiredEmptyCookie, expiredEmptyCookie))) 
      })
    
    Http4sServerInterpreter[IO]().toRoutes(List(sessionImpl, logoutImpl))
  }
  
  def apply(authService: AuthService, authConfig: AuthConfig) =
    HttpRoutes.of[IO]:
      case GET -> Root / "login" =>
        Ok(fs2.io.readClassLoaderResource[IO]("login.html"))

      case req @ POST -> Root / "api" / "auth" / "login" =>

        req.as[UrlForm].flatMap { urlForm =>

          (urlForm.getFirst("username"), urlForm.getFirst("password")) match
            case (Some(username), Some(password)) =>
              IO.fromFuture(IO(authService.authenticate(api.Credentials(username, password)))).flatMap:
                case api.InvalidCredentials()     => BadRequest("Invalid credentials")
                case api.Authentication(accessToken, refreshToken) =>

                  val accessTokenCookie  = ResponseCookie("access_token", accessToken, path = Some("/"), secure = authConfig.secureCookies, httpOnly = true)
                  val refreshCookie      = ResponseCookie("refresh_token", refreshToken, path = Some("/"), secure = authConfig.secureCookies, httpOnly = true)
                  val xsrfCookie         = ResponseCookie("XSRF-TOKEN", UUID.randomUUID().toString, path = Some("/"), secure = authConfig.secureCookies, httpOnly = false)
                  val locationHeader     = `Location`.apply(Uri.unsafeFromString("/"))

                  Found().map(_.addCookie(accessTokenCookie).addCookie(refreshCookie).addCookie(xsrfCookie).putHeaders(locationHeader))
                case api.AuthenticationResponse.Empty      => InternalServerError("Something went wrong")
            case _ =>
              BadRequest("Missing username or password")
        }

      case req @ POST -> Root / "api" / "auth" / "refresh" =>
        req.cookies.find(_.name == "refresh_token").map(_.content) match
          case None        => unauthorizedResponse
          case Some(token) =>
            IO.fromFuture(IO(authService.refresh(api.Authentication("", refreshToken = token)))).flatMap:
              case api.AuthenticationResponse.Empty   => InternalServerError("Something went wrong")
              case api.InvalidCredentials()           => unauthorizedResponse
              case api.Authentication(accessToken, refreshToken) =>
                val accessTokenCookie  = ResponseCookie("access_token", accessToken, path = Some("/"), secure = authConfig.secureCookies, httpOnly = true)
                val refreshCookie      = ResponseCookie("refresh_token", refreshToken, path = Some("/"), secure = authConfig.secureCookies, httpOnly = true)
                Ok().map(_.addCookie(accessTokenCookie))
}

package nl.amony.service.auth

import cats.effect.IO
import nl.amony.service.auth.api.AuthServiceGrpc.AuthService
import org.http4s.UrlForm.given
import org.http4s.dsl.io.*
import org.http4s.headers.{Location, `WWW-Authenticate`}
import org.http4s.{Challenge, Header, HttpDate, HttpRoutes, ResponseCookie, Uri, UrlForm}
import scribe.Logging

import java.util.UUID
import scala.util.{Failure, Success}

object AuthRoutes extends Logging {

  private val unauthorizedResponse = Unauthorized(`WWW-Authenticate`(Challenge("Bearer", "api")))

  def apply(authService: AuthService, authConfig: AuthConfig) =
    HttpRoutes.of[IO]:
      case GET -> Root / "login" =>
        Ok(fs2.io.readClassLoaderResource[IO]("login.html"))

      case req @ GET -> Root / "api" / "auth" / "session" =>
        req.cookies.find(_.name == "access_token").map(_.content) match
          case None          => unauthorizedResponse
          case Some(session) =>
            authConfig.jwt.algorithm.decode(session) match
              case Failure(_)       => unauthorizedResponse
              case Success(decoded) => Ok(decoded.toJson)

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
                
      case POST -> Root / "api" / "auth" / "logout" =>

        val accessTokenCookie = ResponseCookie("access_token", "", path = Some("/"), secure = authConfig.secureCookies, httpOnly = true, expires = Some(HttpDate.MinValue))
        val refreshCookie     = ResponseCookie("refresh_token", "", path = Some("/"), secure = authConfig.secureCookies, httpOnly = true, expires = Some(HttpDate.MinValue))
        val xsrfCookie        = ResponseCookie("XSRF-TOKEN", "", path = Some("/"), secure = authConfig.secureCookies, httpOnly = false, expires = Some(HttpDate.MinValue))

        Ok().map(_.addCookie(accessTokenCookie).addCookie(refreshCookie).addCookie(xsrfCookie))
}

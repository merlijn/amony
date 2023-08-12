package nl.amony.service.auth

import cats.effect.IO
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import nl.amony.lib.cats.FutureOps
import nl.amony.service.auth.api.AuthServiceGrpc.AuthService
import org.http4s.{Headers, HttpRoutes, ResponseCookie}
import org.http4s.circe.toMessageSyntax
import org.http4s.dsl.io.*
import scribe.Logging

case class WebCredentials(username: String, password: String)

object AuthRoutes extends Logging {

  implicit val credDecoder: Codec[WebCredentials] = deriveCodec[WebCredentials]

  def apply(authService: AuthService) = {

    HttpRoutes.of[IO] {
      case req @ POST -> Root / "api" / "identity" / "login" =>

        req.decodeJson[WebCredentials].flatMap { credentials =>
          authService.login(api.Credentials(credentials.username, credentials.password)).toIO.flatMap {
            case api.InvalidCredentials()     => BadRequest("Invalid credentials")
            case api.Authentication(_, token) => Ok("").map(_.addCookie(ResponseCookie("session", token, path = Some("/"))))
          }
        }
      case POST -> Root / "api" / "identity" / "logout" =>
        Ok("")
    }
  }
}

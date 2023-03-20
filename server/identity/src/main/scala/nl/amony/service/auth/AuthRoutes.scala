package nl.amony.service.auth

import cats.effect.IO
import io.circe.generic.semiauto.deriveCodec
import nl.amony.lib.cats.FutureOps
import nl.amony.service.auth.api.AuthServiceGrpc.AuthService
import org.http4s.HttpRoutes
import org.http4s.circe.toMessageSyntax
import org.http4s.dsl.io._
import scribe.Logging

case class WebCredentials(username: String, password: String)

object AuthRoutes extends Logging {

  implicit val credDecoder = deriveCodec[WebCredentials]

  def apply(authService: AuthService) = {

    HttpRoutes.of[IO] {
      case req @ POST -> Root / "api" / "identity" / "login" =>

        req.decodeJson[WebCredentials].flatMap { credentials =>
          authService.login(api.Credentials(credentials.username, credentials.password)).toIO.flatMap {
            case api.InvalidCredentials() => NotFound()
            case api.Authentication(_, token) => Ok(token)
          }
        }
      case POST -> Root / "api" / "identity" / "logout" =>
        Ok("")
    }
  }
}

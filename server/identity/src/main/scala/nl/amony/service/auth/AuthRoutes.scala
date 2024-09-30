package nl.amony.service.auth

import cats.effect.IO
import io.circe.*
import nl.amony.lib.cats.FutureOps
import nl.amony.service.auth.api.AuthServiceGrpc.AuthService
import org.http4s.circe.toMessageSyntax
import org.http4s.dsl.io.*
import org.http4s.{HttpRoutes, ResponseCookie}
import scribe.Logging

case class WebCredentials(username: String, password: String) derives Codec.AsObject

object AuthRoutes extends Logging {

  def apply(authService: AuthService) =
    HttpRoutes.of[IO]:
      case req @ POST -> Root / "api" / "identity" / "login" =>

        req.decodeJson[WebCredentials].flatMap: credentials =>
          authService.login(api.Credentials(credentials.username, credentials.password)).toIO.flatMap:
            case api.InvalidCredentials()     => BadRequest("Invalid credentials")
            case api.Authentication(_, token) => Ok("").map(_.addCookie(ResponseCookie("session", token, path = Some("/"))))
            case api.LoginResponse.Empty      => InternalServerError("Something went wrong")
        
      case POST -> Root / "api" / "identity" / "logout" => Ok("")
}

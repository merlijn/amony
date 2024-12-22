package nl.amony.service.auth

import cats.effect.IO
import io.circe.*
import nl.amony.service.auth.api.AuthServiceGrpc.AuthService
import org.http4s.circe.toMessageSyntax
import org.http4s.dsl.io.*
import org.http4s.{HttpRoutes, ResponseCookie}
import scribe.Logging

case class WebCredentials(username: String, password: String) derives Codec.AsObject

object AuthRoutes extends Logging {

  def apply(authService: AuthService) =
    HttpRoutes.of[IO]:
      case req @ GET -> Root / "login" =>
        Ok(fs2.io.readClassLoaderResource[IO]("login.html"))

      case req @ POST -> Root / "api" / "login" =>

        req.decodeJson[WebCredentials].flatMap: credentials =>
          IO.fromFuture(IO(authService.login(api.Credentials(credentials.username, credentials.password)))).flatMap:
            case api.InvalidCredentials()     => BadRequest("Invalid credentials")
            case api.Authentication(_, token) => Ok("").map(_.addCookie(ResponseCookie("session", token, path = Some("/"))))
            case api.LoginResponse.Empty      => InternalServerError("Something went wrong")
        
      case POST -> Root / "api" / "logout" => Ok("")
}

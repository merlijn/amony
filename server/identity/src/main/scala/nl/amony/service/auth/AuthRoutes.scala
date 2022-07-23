package nl.amony.service.auth

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.HttpCookie
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.generic.semiauto.deriveCodec
import nl.amony.service.auth.api.AuthService.AuthServiceGrpc.AuthService
import nl.amony.service.auth.api.AuthService.Credentials
import nl.amony.service.auth.api.AuthService.Authentication
import nl.amony.service.auth.api.AuthService.InvalidCredentials
import scribe.Logging

import scala.concurrent.duration.DurationInt

case class WebCredentials(username: String, password: String)

object AuthRoutes extends Logging {

  implicit val credDecoder = deriveCodec[WebCredentials]
  implicit val timeout     = Timeout(5.seconds)

  def apply(userApi: AuthService): Route = {

    pathPrefix("api" / "identity") {
      (path("login") & post & entity(as[WebCredentials])) { credentials =>
        onSuccess(userApi.login(Credentials(credentials.username, credentials.password))) {
          case InvalidCredentials(_) =>
            logger.info("Received InvalidCredentials")
            complete(StatusCodes.BadRequest)
          case Authentication(userId, token, _) =>
            logger.info("Received Authentication")
            val cookie = HttpCookie("session", token, path = Some("/"))
            setCookie(cookie) { complete("OK") }
        }

      } ~ (path("logout") & post) {

        setCookie(HttpCookie("session", "", path = Some("/"))) {
          complete("OK")
        }
      } ~ {
        complete(StatusCodes.NotFound)
      }
    }
  }
}

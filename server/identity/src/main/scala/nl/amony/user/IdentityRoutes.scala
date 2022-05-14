package nl.amony.user

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.HttpCookie
import akka.http.scaladsl.server.Directives.{path, _}
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import io.circe.generic.semiauto.deriveCodec
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import nl.amony.user.actor.UserProtocol.{Authentication, InvalidCredentials}

import scala.concurrent.duration.DurationInt

case class Credentials(username: String, password: String)

object IdentityRoutes {

  implicit val credDecoder = deriveCodec[Credentials]
  implicit val timeout = Timeout(5.seconds)

  def createRoutes(userApi: UserApi, tokenHelper: AuthenticationTokenHelper): Route = {

    pathPrefix("api" / "identity") {
      (path("login") & post & entity(as[Credentials])) { credentials =>

        onSuccess(userApi.login(credentials.username, credentials.password)) {
          case InvalidCredentials     =>
            complete(StatusCodes.BadRequest)
          case Authentication(userId) =>
            val cookie = HttpCookie("session", tokenHelper.createToken(userId), path = Some("/"))
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
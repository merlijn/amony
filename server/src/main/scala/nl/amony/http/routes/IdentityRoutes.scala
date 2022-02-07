package nl.amony.http.routes

import nl.amony.http.RouteDeps
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives.{path, _}
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.syntax._
import io.circe.generic.semiauto.deriveCodec
import akka.http.scaladsl.model.headers.HttpCookie
import nl.amony.http.util.Auth

trait IdentityRoutes { 

  self: RouteDeps =>

  case class Credentials(username: String, password: String)

  implicit val credDecoder = deriveCodec[Credentials]

  val identityRoutes =
    pathPrefix("api" / "identity") {
      (path("login") & post & entity(as[Credentials])) { credentials =>

        if (api.users.login(credentials.username, credentials.password)) {

          val cookie = HttpCookie("session", Auth.createToken(), path = Some("/"))

          setCookie(cookie) {
            complete("OK")
          }
        }
        else
          complete(StatusCodes.BadRequest)
      } ~ (path("logout") & post) {

          setCookie(HttpCookie("session", "", path = Some("/"))) {
            complete("OK")
          }
      } ~ {
        complete(StatusCodes.NotFound)
      }
    }
}

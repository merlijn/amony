package nl.amony.http.routes

import nl.amony.http.RouteDeps
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives.{path, _}
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.syntax._
import io.circe.generic.semiauto.deriveCodec
import akka.http.scaladsl.model.headers.HttpCookie
import nl.amony.actor.user.UserProtocol.{Authentication, InvalidCredentials}
import nl.amony.http.util.AuthenticationTokenHelper
import nl.amony.http.util.HttpDirectives.postWithData

trait IdentityRoutes { 

  self: RouteDeps =>

  def tokenHelper(): AuthenticationTokenHelper

  case class Credentials(username: String, password: String)

  implicit val credDecoder = deriveCodec[Credentials]

  val identityRoutes =
    pathPrefix("api" / "identity") {
      (path("login") & postWithData[Credentials]) { credentials =>

        onSuccess(api.users.login(credentials.username, credentials.password)) {
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

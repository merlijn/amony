package nl.amony.modules.auth.api

import sttp.model.{Method, StatusCode}
import sttp.tapir.*

case class SecurityInput(accessToken: Option[String], xsrfCookie: Option[String], xXsrfHeader: Option[String], method: Method)

val securityInput: EndpointInput[SecurityInput] =
  cookie[Option[String]](authCookieName)
    .and(cookie[Option[String]]("XSRF-TOKEN"))
    .and(extractFromRequest(_.header("X-XSRF-TOKEN")))
    .and(extractFromRequest(_.method))
    .mapTo[SecurityInput]

/** Security input for endpoints that only need the double-submit token (e.g. refresh/logout). */
val xsrfSecurityInput: EndpointInput[(Option[String], Option[String])] =
  cookie[Option[String]]("XSRF-TOKEN")
    .and(extractFromRequest(_.header("X-XSRF-TOKEN")))

val unauthorizedOutput = oneOfVariantSingletonMatcher(statusCode(StatusCode.Unauthorized))(SecurityError.Unauthorized)
val forbiddenOutput    = oneOfVariantSingletonMatcher(statusCode(StatusCode.Forbidden))(SecurityError.Forbidden)

val securityErrors = List(
  oneOfVariantSingletonMatcher(statusCode(StatusCode.Unauthorized))(SecurityError.Unauthorized),
  oneOfVariantSingletonMatcher(statusCode(StatusCode.Forbidden))(SecurityError.Forbidden)
)

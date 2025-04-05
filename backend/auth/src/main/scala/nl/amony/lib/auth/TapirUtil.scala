package nl.amony.lib.auth

import sttp.model.StatusCode
import sttp.tapir.*

case class SecurityInput(accessToken: Option[String], xsrfCookie: Option[String], xXsrfHeader: Option[String])

val securityInput: EndpointInput[SecurityInput] =
  cookie[Option[String]]("access_token")
    .and(extractFromRequest(_.header("X-XSRF-TOKEN")))
    .and(cookie[Option[String]]("XSRF-TOKEN"))
    .mapTo[SecurityInput]

val unauthorizedOutput = oneOfVariantSingletonMatcher(statusCode(StatusCode.Unauthorized))(SecurityError.Unauthorized)
val forbiddenOutput    = oneOfVariantSingletonMatcher(statusCode(StatusCode.Forbidden))(SecurityError.Forbidden)

val securityErrors = List(
  oneOfVariantSingletonMatcher(statusCode(StatusCode.Unauthorized))(SecurityError.Unauthorized),
  oneOfVariantSingletonMatcher(statusCode(StatusCode.Forbidden))(SecurityError.Forbidden),
)

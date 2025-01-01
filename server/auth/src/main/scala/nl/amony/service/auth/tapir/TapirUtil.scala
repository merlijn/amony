package nl.amony.service.auth.tapir

import cats.effect.IO
import nl.amony.service.auth.RouteAuthenticator.validateSecurityInput
import nl.amony.service.auth.{AuthToken, JwtDecoder, Role}
import sttp.tapir.*
import sttp.model.StatusCode

case class SecurityInput(accessToken: Option[String], xsrfCookie: Option[String], xXsrfHeader: Option[String])

enum SecurityError:
  case Unauthorized
  case Forbidden

val securityInput: EndpointInput[SecurityInput] =
  cookie[Option[String]]("access_token")
    .and(extractFromRequest(_.header("X-XSRF-TOKEN")))
    .and(cookie[Option[String]]("XSRF-TOKEN"))
    .mapTo[SecurityInput]

val securityErrors = List(
  oneOfVariantSingletonMatcher(statusCode(StatusCode.Unauthorized))(SecurityError.Unauthorized),
  oneOfVariantSingletonMatcher(statusCode(StatusCode.Forbidden))(SecurityError.Forbidden),
)

class TapirAuthenticator(decoder: JwtDecoder):

  def publicEndpoint(securityInput: SecurityInput): IO[Either[SecurityError, AuthToken]] =
    IO.pure(Right(AuthToken.anonymous))

  def requireRole(requiredRole: Role)(securityInput: SecurityInput): IO[Either[SecurityError, AuthToken]] =
    IO.pure(validateSecurityInput(decoder, securityInput, requiredRole))

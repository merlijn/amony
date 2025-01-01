package nl.amony.service.auth

import cats.effect.IO
import nl.amony.service.auth.RouteAuthenticator.validateSecurityInput
import nl.amony.service.auth.tapir.{SecurityError, SecurityInput}
import org.http4s.dsl.io.*
import org.http4s.headers.`WWW-Authenticate`
import org.http4s.{Challenge, HttpRoutes, Request, Response}
import org.typelevel.ci.CIStringSyntax
import scribe.Logging

object RouteAuthenticator:
  def securityInputFromRequest(req: Request[IO]): SecurityInput =
    SecurityInput(
      accessToken  = req.cookies.find(_.name == "access_token").map(_.content),
      xsrfCookie   = req.cookies.find(_.name == "XSRF-TOKEN").map(_.content),
      xXsrfHeader  = req.headers.get(ci"X-XSRF-TOKEN").map(_.head.value)
    )

  def validateSecurityInput(decoder: JwtDecoder, securityInput: SecurityInput, requiredRole: Role): Either[SecurityError, AuthToken] = {
    for {
      accessToken <- securityInput.accessToken.toRight(SecurityError.Unauthorized)
      decoded     <- decoder.decode(accessToken).toEither.left.map(_ => SecurityError.Unauthorized)
      _           <- if decoded.roles.contains(requiredRole) then Right(()) else Left(SecurityError.Forbidden)
      xsrfToken   <- securityInput.xsrfCookie.toRight(SecurityError.Unauthorized)
      xXsrfHeader <- securityInput.xXsrfHeader.toRight(SecurityError.Unauthorized)
      _           <- if xsrfToken == xXsrfHeader then Right(()) else Left(SecurityError.Unauthorized)
    } yield AuthToken(decoded.userId, decoded.roles)
  }

class RouteAuthenticator(decoder: JwtDecoder) extends Logging:
  private val unauthorizedResponse = Unauthorized(`WWW-Authenticate`(Challenge("Bearer", "api")))

  def authenticated(req: Request[IO], requiredRole: Role)(response: => IO[Response[IO]]): IO[Response[IO]] = {

    val securityInput = RouteAuthenticator.securityInputFromRequest(req)

    IO.pure(validateSecurityInput(decoder, securityInput, requiredRole)).flatMap:
      case Right(_)  => response
      case Left(msg) =>
        logger.info(s"Unauthorized request: $msg")
        unauthorizedResponse
  }

  def authenticated(requiredRole: Role)(pf: PartialFunction[Request[IO], IO[Response[IO]]]): HttpRoutes[IO] = {
    HttpRoutes.of[IO] {
      case req if pf.isDefinedAt(req) => authenticated(req, requiredRole)(pf(req))
    }
  }

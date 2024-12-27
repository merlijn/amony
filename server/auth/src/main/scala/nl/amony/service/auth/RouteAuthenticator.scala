package nl.amony.service.auth

import org.http4s.dsl.io.*
import org.http4s.headers.`WWW-Authenticate`
import org.http4s.{Challenge, HttpRoutes, Request, Response}
import cats.effect.IO
import cats.data.EitherT
import org.typelevel.ci.CIString
import scribe.Logging

class RouteAuthenticator(decoder: JwtDecoder) extends Logging:
  private val unauthorizedResponse = Unauthorized(`WWW-Authenticate`(Challenge("Bearer", "api")))

  def authenticated(req: Request[IO], requiredRole: Role)(response: => IO[Response[IO]]): IO[Response[IO]] = {

    val validation: EitherT[IO, String, Unit] = for {
      accessToken <- EitherT.fromOption[IO](req.cookies.find(_.name == "access_token").map(_.content), "Missing access token")
      decoded     <- EitherT.fromEither[IO](decoder.decode(accessToken).toEither.left.map(_ => "Invalid access token"))
      _           <- EitherT.cond[IO](decoded.roles.contains(requiredRole), (), "Missing required role")
      xsrfToken   <- EitherT.fromOption[IO](req.cookies.find(_.name == "XSRF-TOKEN").map(_.content), "Missing XSRF token")
      xXsrfHeader <- EitherT.fromOption[IO](req.headers.get(CIString("X-XSRF-TOKEN")).map(_.head.value), "Missing X-XSRF-TOKEN header")
      _           <- EitherT.cond[IO](xsrfToken == xXsrfHeader, (), "XSRF token mismatch")
    } yield ()

    validation.value.flatMap:
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

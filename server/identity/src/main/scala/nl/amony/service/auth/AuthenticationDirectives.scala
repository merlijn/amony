package nl.amony.service.auth

import cats.Monad
import org.http4s.{Request, Response}

object AuthenticationDirectives:

  def authenticated[F[_]](req: Request[F], requiredRole: String)(response: => F[Response[F]])(using F : Monad[F]): F[Response[F]] = response

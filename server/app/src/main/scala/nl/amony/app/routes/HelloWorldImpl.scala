package nl.amony.app.routes

import cats.data.OptionT
import cats.effect.{IO, IOLocal, Resource}
import cats.implicits.{catsSyntaxApplyOps, toSemigroupKOps}
import nl.amony.app.api.*
import nl.amony.service.auth.{AuthToken, Role, Roles}
import org.http4s.HttpRoutes
import org.http4s.headers.Authorization

extension (auth: IO[AuthToken])
  def requireRole[T](role: Role)(fn: => IO[T]): IO[T] =
    auth.flatMap { token =>
      if (token.roles.contains(role)) fn
      else IO.raiseError(UnauthorizedUser())
    }

class HelloWorldImpl(authentication: IO[AuthToken]) extends HelloWorldService[IO] {
  def hello(name: String, town: Option[String]): IO[Greeting] =
    authentication.requireRole(Roles.Admin) {
      if (name == "error")
        IO.raiseError(MyError())
      else
        town match {
          case None    => IO.pure(Greeting(s"Hello $name!"))
          case Some(t) => IO.pure(Greeting(s"Hello $name from $t!"))
        }
    }
  }

object HelloWorldRoutes {

  def withRequestInfo(local: IOLocal[Option[AuthToken]])(routes: HttpRoutes[IO]): HttpRoutes[IO] =
    HttpRoutes[IO] { request =>
      val requestInfo = for {
        auth <- request.headers.get[Authorization].map(_.credentials.toString)
      } yield AuthToken(auth, auth.split(",").map(Role.apply).toSet)

      OptionT.liftF(local.set(requestInfo)) *> routes(request)
    }

  private val docs: HttpRoutes[IO] =
    smithy4s.http4s.swagger.docs[IO](HelloWorldService)

  def routes: Resource[IO, HttpRoutes[IO]] =
    IOLocal(Option.empty[AuthToken]).toResource.flatMap { local =>
      val getAuthorization: IO[AuthToken] = local.get.flatMap {
        case Some(value) => IO.pure(value)
        case None        => IO.pure(AuthToken.anonymous)
      }
      smithy4s.http4s.SimpleRestJsonBuilder
        .routes(new HelloWorldImpl(getAuthorization))
        .resource
        .map { withRequestInfo(local) }
        .map { routes =>
          docs <+> routes
        }
    }
}

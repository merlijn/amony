package nl.amony.app.routes

import cats.effect.IO
import nl.amony.service.auth.AuthToken
import org.http4s.HttpRoutes
import sttp.model.headers.{CookieValueWithMeta, CookieWithMeta}
import sttp.tapir.*
import sttp.model.StatusCode
import sttp.tapir.server.http4s.Http4sServerInterpreter

object HelloTapir:

  case class SecurityInput(bearerToken: Option[String], xsrfCookie: Option[String], xXsrfToken: Option[String], xXsrfHeader: Option[String])

  enum EndpointErrorOut:
    case Unauthorized
    case NotFound
    case Other(message: String)

  val errorOutput: EndpointOutput[EndpointErrorOut] = oneOf(
    oneOfVariantSingletonMatcher(statusCode(StatusCode.Unauthorized))(EndpointErrorOut.Unauthorized),
    oneOfVariantSingletonMatcher(statusCode(StatusCode.NotFound))(EndpointErrorOut.NotFound),
    oneOfVariant(stringBody.mapTo[EndpointErrorOut.Other])
  )

  val securityInput: EndpointInput[SecurityInput] =
    extractFromRequest(_.header("Authorization"))
    .and(extractFromRequest(_.header("X-XSRF-TOKEN")))
    .and(extractFromRequest(_.cookies.collectFirst { case Right(CookieWithMeta("access_token", value)) => value.value}))
    .and(extractFromRequest(_.cookies.collectFirst { case Right(CookieWithMeta("XSRF-TOKEN", value)) => value.value}))
    .mapTo[SecurityInput]


  def secureFn(securityInput: SecurityInput): IO[Either[EndpointErrorOut, AuthToken]] =
    IO.pure(Right(AuthToken("userId", Set.empty)))

  val hello = endpoint
    .get
    .securityIn(securityInput)
    .errorOut(errorOutput)
    .in("tapir" / "hello").in(query[String]("name"))
    .out(stringBody)

  val helloImpl =
    hello
      .serverSecurityLogic(secureFn)
      .serverLogicSuccess(_ => sayHello _)


  def sayHello(name: String): IO[String] =
    IO.pure(s"Hello, $name!")

  val helloRoutes: HttpRoutes[IO] =
    Http4sServerInterpreter[IO]().toRoutes(helloImpl)

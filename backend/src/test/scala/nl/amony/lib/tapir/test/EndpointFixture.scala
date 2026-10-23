package nl.amony.lib.tapir.test

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.impl.cats.CatsMonadError
import sttp.client4.testing.StreamBackendStub
import sttp.client4.{Request, Response, StreamBackend, basicRequest}
import sttp.model.{Method, Uri}
import sttp.monad.MonadError
import sttp.tapir.AnyEndpoint
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.stub4.TapirStreamStubInterpreter

import nl.amony.lib.tapir.dsl.ServerEndpoints

/**
 * Test fixture that wires an [[AnyEndpoint]] definition to the [[ServerEndpoint]] implementing it and
 * drives it through Tapir's stub interpreter. It removes the http4s/HTTP-server boilerplate from
 * endpoint tests and can be extended with cross-cutting helpers (e.g. authentication) later.
 *
 * Usage:
 * {{{
 *   "..." in new EndpointFixture(authRoutes, AuthRoutes.loginEndpoint) {
 *     val response = request(path = "/api/auth/login/dex").sendUnsafeSync()
 *   }
 * }}}
 */
trait EndpointFixture(
  endpoints: ServerEndpoints[IO],
  definition: AnyEndpoint,
  host: String   = "app.example.com",
  scheme: String = "https"
) {

  private given MonadError[IO] = new CatsMonadError[IO]

  private val name = definition.info.name.getOrElse(throw new IllegalArgumentException("endpoint definition has no name"))

  private val serverEndpoint: ServerEndpoint[Fs2Streams[IO], IO] =
    endpoints.find(_.endpoint.info.name.contains(name)).getOrElse(throw new IllegalArgumentException(s"$name endpoint not found"))

  private val stubBackend: StreamBackend[IO, Fs2Streams[IO]] =
    TapirStreamStubInterpreter(StreamBackendStub[IO, Fs2Streams[IO]](summon[MonadError[IO]]))
      .whenServerEndpointRunLogic(serverEndpoint)
      .backend()

  /**
   * A request for this endpoint, with the HTTP method taken from the definition. `path` is the full
   * request path, starting with `/` (e.g. `request(path = "/api/auth/login/dex")`); `queryParams` are
   * appended to the query string.
   *
   * The forwarded host/scheme headers are set so `requestOrigin` resolves deterministically.
   */
  def request(path: String, queryParams: Map[String, String] = Map.empty) =
    val method = definition.method.getOrElse(Method.GET)
    val uri    = Uri.unsafeParse(s"http://localhost$path").addParams(queryParams)

    basicRequest.method(method, uri)
      .header("X-Forwarded-Host", host)
      .header("X-Forwarded-Proto", scheme)

  /**
   * Sends a request built via [[request]] against this fixture's stub backend and blocks for the
   * response. Named `sendUnsafeSync` rather than `send` because sttp's `Request.send()` overload
   * (implicit backend) is deprecated and a member method shadows any same-named extension.
   */
  extension [T](request: Request[T])
    def sendUnsafeSync(): Response[T] = request.send(stubBackend).unsafeRunSync()
}

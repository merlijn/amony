package nl.amony.lib.tapir.test

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import sttp.capabilities.fs2.Fs2Streams
import sttp.client3.testing.SttpBackendStub
import sttp.client3.{Identity, RequestT, Response, SttpBackend, basicRequest}
import sttp.client4.impl.cats.CatsMonadError
import sttp.model.{Method, Uri}
import sttp.monad.MonadError
import sttp.tapir.AnyEndpoint
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.stub.TapirStubInterpreter

import nl.amony.lib.tapir.dsl.ServerEndpoints

/**
 * Test fixture that wires an [[AnyEndpoint]] definition to the [[ServerEndpoint]] implementing it and
 * drives it through Tapir's stub interpreter. It removes the http4s/HTTP-server boilerplate from
 * endpoint tests and can be extended with cross-cutting helpers (e.g. authentication) later.
 *
 * Usage:
 * {{{
 *   "..." in new EndpointFixture(authRoutes, AuthRoutes.loginEndpoint) {
 *     val response = request("provider" -> "dex").sendUnsafeSync()
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

  private val stubBackend: SttpBackend[IO, Fs2Streams[IO]] =
    TapirStubInterpreter(SttpBackendStub[IO, Fs2Streams[IO]](summon[MonadError[IO]]))
      .whenServerEndpointRunLogic(serverEndpoint)
      .backend()

  /**
   * A request for this endpoint, with the HTTP method and path taken from the definition. Parameters
   * whose name matches a path placeholder (e.g. `request("provider" -> "dex")`) fill that placeholder
   * in; any other name/value pairs are appended as query parameters.
   *
   * The forwarded host/scheme headers are set so `requestOrigin` resolves deterministically.
   */
  def request(params: (String, String)*) =
    val method   = definition.method.getOrElse(Method.GET)
    val template = definition.showPathTemplate(showQueryParam = None)

    val (pathParams, queryParams) = params.partition { case (key, _) => template.contains(s"{$key}") }
    val path                      = pathParams.foldLeft(template) { case (path, (key, value)) => path.replace(s"{$key}", value) }
    val query                     = if queryParams.isEmpty then "" else queryParams.map((key, value) => s"$key=$value").mkString("?", "&", "")

    basicRequest.method(method, Uri.unsafeParse(s"http://localhost$path$query"))
      .header("X-Forwarded-Host", host)
      .header("X-Forwarded-Proto", scheme)

  /**
   * Sends a request built via [[request]] against this fixture's stub backend and blocks for the
   * response. Named `sendUnsafeSync` rather than `send` because sttp's `RequestT.send()` overload
   * (implicit backend) is deprecated and a member method shadows any same-named extension.
   */
  extension [T](request: RequestT[Identity, T, Any])
    def sendUnsafeSync(): Response[T] = request.send(stubBackend).unsafeRunSync()
}

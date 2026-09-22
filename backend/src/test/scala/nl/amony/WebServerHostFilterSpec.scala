package nl.amony

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.implicits.*
import org.scalatest.wordspec.AnyWordSpecLike
import org.typelevel.ci.CIStringSyntax

class WebServerHostFilterSpec extends AnyWordSpecLike {

  private val inner: HttpRoutes[IO] = HttpRoutes.of[IO] { case GET -> Root / "ping" => Ok("pong") }

  private def request(host: String, forwardedHost: Option[String] = None): Request[IO] =
    val headers = Headers(Header.Raw(ci"Host", host)) ++ forwardedHost.map(h => Headers(Header.Raw(ci"X-Forwarded-Host", h))).getOrElse(Headers.empty)
    Request[IO](Method.GET, uri"/ping", headers = headers)

  private def status(allowedHosts: List[String], req: Request[IO]) =
    WebServer.hostFilter(allowedHosts)(inner).run(req).value.unsafeRunSync().map(_.status)

  "WebServer.hostFilter" should {
    "allow a request whose host is listed" in {
      assert(status(List("good.example.com"), request("good.example.com")).contains(Status.Ok))
    }
    "reject a request whose host is not listed" in {
      assert(status(List("good.example.com"), request("evil.example.com")).contains(Status.MisdirectedRequest))
    }
    "prefer X-Forwarded-Host over Host" in {
      assert(status(List("admin.example.com"), request("backend:8182", Some("admin.example.com"))).contains(Status.Ok))
    }
    "reject when the forwarded host is not listed even if Host is" in {
      assert(status(List("backend:8182"), request("backend:8182", Some("evil.example.com"))).contains(Status.MisdirectedRequest))
    }
  }
}

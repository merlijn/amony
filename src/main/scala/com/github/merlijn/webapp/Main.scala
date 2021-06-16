package com.github.merlijn.webapp


trait WebServer {

  import scala.concurrent.ExecutionContext.global
  import com.typesafe.config.Config
  import com.typesafe.config.ConfigFactory

  import cats.effect.*
  import cats.syntax.all.*
  import org.http4s
  import org.http4s.HttpRoutes
  import org.http4s.dsl.io.*
  import org.http4s.implicits.*
  import org.http4s.blaze.server.BlazeServerBuilder
  import org.http4s.server.Router

  object config {
    val conf = ConfigFactory.load

    val port = conf.getInt("webapp.http.port")
    val hostname = conf.getString("webapp.http.hostName")
  }

  val helloWorldService = HttpRoutes.of[IO] {
    case GET -> Root / "movie" / name => Ok(s"""{ "title" : "Hello World!"}""")
  }.orNotFound

  BlazeServerBuilder[IO](global)
    .bindHttp(config.port, config.hostname)
    .withHttpApp(helloWorldService)
    .serve
    .compile
    .drain
    .unsafeRunSync()(cats.effect.unsafe.implicits.global)
}

object Main extends App {

  Lib.index()

}

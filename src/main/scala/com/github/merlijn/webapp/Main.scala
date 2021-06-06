package com.github.merlijn.webapp

import cats.effect.*
import cats.syntax.all.*
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.implicits.*
import org.http4s.blaze.server.BlazeServerBuilder
import org.http4s.server.Router
import scala.concurrent.ExecutionContext.global

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

object Main extends App {

  object config {
    val conf = ConfigFactory.load

    val port = conf.getInt("webapp.http.port")
    val hostname = conf.getString("webapp.http.hostName")
  }

  val helloWorldService = HttpRoutes.of[IO] {
    case GET -> Root / "hello" / name => Ok(s"Hello, $name.")
  }

}

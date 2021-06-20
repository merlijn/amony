package com.github.merlijn.webapp

import scala.concurrent.ExecutionContext.global

import cats.effect.*
import cats.syntax.all.*
import org.http4s
import org.http4s.HttpRoutes
import org.http4s.dsl.io.*
import org.http4s.implicits.*
import org.http4s.server.staticcontent.*
import org.http4s.blaze.server.BlazeServerBuilder
import org.http4s.server.Router
import java.util.concurrent.*
import Model.*
import io.circe.syntax.*

trait WebServer extends Logging {

  val index = Lib.index(Config.path)

  index.foreach { i =>

    Lib.writeThumbnail(i.fileName, i.duration / 3, Some(s"${Config.indexPath}/${i.id}.jpeg"))
  }

  val videos: List[Video] =
    index.map { info => 
      Video(
        id        = info.id,
        title     = info.fileName,
        thumbnail = s"${Config.hostname}:${Config.port}/files/${info.id}",
        tags      = Seq.empty
      ) 
    }.toList

  val json = videos.asJson.toString

  logger.info(json)

  val apiRoute = HttpRoutes.of[IO] {
    case GET -> Root / "movies" => Ok(json)
  }

  val router = Router(
    "api" -> apiRoute,
    "files" -> fileService(FileService.Config(Config.indexPath))
  ).orNotFound

  BlazeServerBuilder[IO](global)
    .bindHttp(Config.port, Config.hostname)
    .withHttpApp(router)
    .serve
    .compile
    .drain
    .unsafeRunSync()(cats.effect.unsafe.implicits.global)
}

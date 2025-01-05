package nl.amony.app.routes

import cats.effect.IO
import fs2.io.file.Files
import nl.amony.app.WebServerConfig
import nl.amony.service.resources.web.ResourceDirectives
import org.http4s.HttpRoutes
import org.http4s.dsl.io.*

object WebAppRoutes:

  def apply(config: WebServerConfig) = {

    val basePath = fs2.io.file.Path.apply(config.webClientPath)
    val indexPath = basePath.resolve("index.html")

    HttpRoutes.of[IO]:
      case req @ GET -> rest =>
        val requestedPath = rest.toString() match
          case "" | "/" => "index.html"
          case other => other

        val targetPath = basePath.resolve(requestedPath.stripMargin('/'))

        Files[IO].exists(targetPath).map {
          case true  => targetPath
          case false => indexPath
        }.flatMap { file =>
          ResourceDirectives.responseFromFile[IO](req, file, 32 * 1024)
        }
  }

package nl.amony.app.routes

import cats.effect.IO
import nl.amony.service.resources.web.ResourceDirectives
import nl.amony.app.{AmonyConfig, WebServerConfig}
import org.http4s.HttpRoutes
import org.http4s.dsl.io.*
import fs2.io.file.Path
import fs2.io.file.Files
import org.http4s.headers.`Accept-Encoding`


object WebAppRoutes:

  def apply(config: WebServerConfig) = {

    val basePath = fs2.io.file.Path.apply(config.webClientPath)

    HttpRoutes.of[IO]:
      case req @ GET -> rest =>
        val requestedPath = rest.toString() match
          case "" | "/" => "index.html"
          case other => other

        val targetPath = basePath.resolve(requestedPath.stripMargin('/'))

        Files[IO].exists(targetPath).map {
          case true  => targetPath
          case false =>
            println(s"requested path not found: '$requestedPath', falling back to index.html'")
            basePath.resolve("index.html")
        }.flatMap { file =>
          ResourceDirectives.fromPath[IO](req, file, 32 * 1024)
        }
  }

package nl.amony.webserver.routes

import cats.effect.IO
import nl.amony.service.resources.web.ResourceDirectives
import nl.amony.webserver.{AmonyConfig, WebServerConfig}
import org.http4s.HttpRoutes
import org.http4s.dsl.io.*

import java.nio.file.{Files, Paths}

object WebAppRoutes:

  def apply(config: WebServerConfig) = 
    HttpRoutes.of[IO]:
      case req @ GET -> rest =>
        val filePath = rest.toString() match 
          case "" | "/" => "index.html"
          case other => other
  
        val targetFile = {
          val requestedFile = Paths.get(config.webClientPath).resolve(filePath.stripMargin('/'))
          if (Files.exists(requestedFile))
            requestedFile
          else
            Paths.get(config.webClientPath).resolve("index.html")
        }
  
        ResourceDirectives.fromPath[IO](req, fs2.io.file.Path.fromNioPath(targetFile), 32 * 1024)

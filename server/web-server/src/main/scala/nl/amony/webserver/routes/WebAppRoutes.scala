package nl.amony.webserver.routes

import akka.http.scaladsl.server.Directives.{extractUnmatchedPath, getFromFile, rawPathPrefix}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._
import nl.amony.webserver.WebServerConfig

import java.nio.file.{Files, Paths}

object WebAppRoutes {
  // routes for the web app (javascript/html) resources
  def apply(config: WebServerConfig): Route =
    rawPathPrefix(Slash) {
      extractUnmatchedPath { urlPath =>
        val filePath = urlPath.toString() match {
          case "" | "/" => "index.html"
          case other    => other
        }

        val targetFile = {
          val requestedFile = Paths.get(config.webClientPath).resolve(filePath)
          if (Files.exists(requestedFile))
            requestedFile
          else
            Paths.get(config.webClientPath).resolve("index.html")
        }

        getFromFile(targetFile.toFile)
      }
    }
}

package nl.amony.webserver.routes

import akka.http.scaladsl.server.Directives.{extractUnmatchedPath, getFromFile, rawPathPrefix}
import akka.http.scaladsl.server.Route
import better.files.File
import akka.http.scaladsl.server.Directives._
import nl.amony.webserver.WebServerConfig

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
          val requestedFile = File(config.webClientPath) / filePath
          if (requestedFile.exists)
            requestedFile
          else
            File(config.webClientPath) / "index.html"
        }

        getFromFile(targetFile.path.toAbsolutePath.toString)
      }
    }
}

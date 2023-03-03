package nl.amony.webserver

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import nl.amony.search.SearchRoutes
import nl.amony.service.auth.AuthRoutes
import nl.amony.service.auth.api.AuthServiceGrpc.AuthService
import nl.amony.service.media.MediaService
import nl.amony.service.media.web.MediaRoutes
import nl.amony.service.resources.{ResourceBucket, ResourceRoutes}
import nl.amony.service.search.api.SearchServiceGrpc.SearchService

import java.nio.file.{Files, Paths}
import scala.concurrent.ExecutionContext

object WebServerRoutes {

  def apply(
         userService: AuthService,
         mediaService: MediaService,
         searchService: SearchService,
         resourceBuckets: Map[String, ResourceBucket],
         config: AmonyConfig
    )(implicit ec: ExecutionContext): Route = {
    import akka.http.scaladsl.server.Directives._

    val identityRoutes = AuthRoutes(userService)
    val resourceRoutes = ResourceRoutes(resourceBuckets, config.api.uploadSizeLimit.toBytes.toLong)
    val searchRoutes   = SearchRoutes(searchService, config.search, config.media.transcode)
    val mediaRoutes    = MediaRoutes(mediaService, config.media.transcode)

    // routes for the web app (javascript/html) resources
    val webAppResources = webAppRoutes(config.api)

    mediaRoutes ~ searchRoutes ~ identityRoutes ~ resourceRoutes ~ webAppResources
  }

  // routes for the web app (javascript/html) resources
  def webAppRoutes(config: WebServerConfig): Route =
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

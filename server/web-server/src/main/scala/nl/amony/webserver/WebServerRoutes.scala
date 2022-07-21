package nl.amony.webserver

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import nl.amony.search
import nl.amony.search.SearchService
import nl.amony.service.auth.{AuthServiceImpl, AuthRoutes}
import nl.amony.service.media.{MediaService, MediaRoutes}
import nl.amony.service.resources.{ResourceService, ResourceRoutes}
import nl.amony.webserver.{AmonyConfig, WebServerConfig}
import nl.amony.webserver.admin.{AdminApi, AdminRoutes}

import java.nio.file.{Files, Paths}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

object WebServerRoutes {

  def apply(
             system: ActorSystem[Nothing],
             userApi: AuthServiceImpl,
             mediaApi: MediaService,
             resourceApi: ResourceService,
             adminApi: AdminApi,
             config: AmonyConfig
    ): Route = {
    implicit val ec: ExecutionContext = system.executionContext
    import akka.http.scaladsl.server.Directives._

    implicit val requestTimeout = Timeout(5.seconds)

    val searchApi      = new SearchService(system)

    val identityRoutes = AuthRoutes(userApi)
    val resourceRoutes = ResourceRoutes(resourceApi, config.api.uploadSizeLimit.toBytes.toLong)
    val searchRoutes   = search.SearchRoutes(system, searchApi, config.search, config.media.transcode)
    val adminRoutes    = AdminRoutes(adminApi, config.api)
    val mediaRoutes    = MediaRoutes(system, mediaApi, config.media.transcode)

    // routes for the web app (javascript/html) resources
    val webAppResources = webAppRoutes(config.api)

    val allApiRoutes =
      if (config.api.enableAdmin)
        mediaRoutes ~ adminRoutes
      else
        mediaRoutes

    allApiRoutes ~ searchRoutes ~ identityRoutes ~ resourceRoutes ~ webAppResources
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

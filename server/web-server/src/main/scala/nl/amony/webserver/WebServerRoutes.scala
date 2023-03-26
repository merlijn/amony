package nl.amony.webserver

import cats.effect.IO
import cats.implicits.toSemigroupKOps
import nl.amony.search.SearchRoutes
import nl.amony.service.auth.AuthRoutes
import nl.amony.service.auth.api.AuthServiceGrpc.AuthService
import nl.amony.service.media.MediaService
import nl.amony.service.media.web.MediaRoutes
import nl.amony.service.resources.{ResourceBucket, ResourceDirectives, ResourceRoutes}
import nl.amony.service.search.api.SearchServiceGrpc.SearchService
import org.http4s.HttpRoutes
import scribe.Logging

import java.nio.file.{Files, Paths}

object WebServerRoutes extends Logging {

  def routes(authService: AuthService,
             mediaService: MediaService,
             searchService: SearchService,
             config: AmonyConfig,
             resourceBuckets: Map[String, ResourceBucket]): HttpRoutes[IO] = {

    import org.http4s._
    import org.http4s.dsl.io._

    // routes for the web app (javascript/html) resources
    val webAppRoutes = HttpRoutes.of[IO] {
      case req@GET -> rest =>
        val filePath = rest.toString() match {
          case "" | "/" => "index.html"
          case other => other
        }

        val targetFile = {
          val requestedFile = Paths.get(config.api.webClientPath).resolve(filePath.stripMargin('/'))
          if (Files.exists(requestedFile))
            requestedFile
          else
            Paths.get(config.api.webClientPath).resolve("index.html")
        }

        logger.info(s"Request for $filePath, serving $targetFile")

        ResourceDirectives.fromPath[IO](req, fs2.io.file.Path.fromNioPath(targetFile), 32 * 1024)
    }

    val routes =
      MediaRoutes.apply(mediaService, config.media.transcode) <+>
        ResourceRoutes.apply(resourceBuckets) <+>
        SearchRoutes.apply(searchService, config.search, config.media.transcode) <+>
        AuthRoutes.apply(authService) <+>
        webAppRoutes

    routes
  }
}

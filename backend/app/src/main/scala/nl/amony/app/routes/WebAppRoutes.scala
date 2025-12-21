package nl.amony.app.routes

import scala.concurrent.duration.DurationInt

import cats.effect.IO
import fs2.io.file.{Files, Path}
import org.http4s.CacheDirective.`max-age`
import org.http4s.dsl.io.*
import org.http4s.headers.`Cache-Control`
import org.http4s.{Headers, HttpRoutes}

import nl.amony.app.WebServerConfig
import nl.amony.service.resources.web.ResourceDirectives

object WebAppRoutes:

  def apply(config: WebServerConfig) = {

    val basePath          = Path.apply(config.webClientPath)
    val indexFile         = basePath.resolve("index.html")
    val chunkSize         = 32 * 1024
    val assetsCachePeriod = 365.days

    HttpRoutes.of[IO]:
      case req @ GET -> rest =>
        val requestedPath = rest.toString() match
          case "" | "/" => "index.html"
          case other    => other

        val requestedFile = basePath.resolve(requestedPath.stripMargin('/'))

        Files[IO].exists(requestedFile).map {
          case true  => requestedFile
          case false => indexFile
        }.flatMap {
          file =>

            val cacheControlHeaders =
              // files in the assets folder are suffixed with a hash and can be cached indefinitely
              if requestedPath.startsWith("/assets/") then { Headers(`Cache-Control`(`max-age`(assetsCachePeriod))) }
              else Headers.empty

            ResourceDirectives.responseFromFile[IO](req, file, chunkSize, additionalHeaders = cacheControlHeaders)
        }
  }

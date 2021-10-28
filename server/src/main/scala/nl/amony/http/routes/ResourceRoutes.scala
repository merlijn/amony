package nl.amony.http.routes

import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.directives.FileAndResourceDirectives
import akka.stream.scaladsl.StreamConverters
import better.files.File
import nl.amony.http.util.CustomDirectives
import nl.amony.http.{RouteDeps, WebServerConfig}
import scribe.Logging

import java.nio.file.Path

trait ResourceRoutes extends Logging {

  self: RouteDeps =>

  val videoPattern = raw"(.+)\.mp4".r
  val videoSegment = raw"(.+)~(\d+)-(\d+)\.mp4".r
  val thumbnailPattern = raw"(.+)\.webp".r

  val resourceRoutes =
    (get & path("files" / "resources" / Segment) & parameters("t".optional)) { (file, timestamp) =>

      file match {

        case thumbnailPattern(id) =>
          onSuccess(api.resources.getThumbnail(id, timestamp.map(_.toLong))) {
            case None     => complete(StatusCodes.NotFound, "")
            case Some(is) =>
              val source = StreamConverters.fromInputStream(() => is, 8192)
              complete(HttpEntity(ContentType(MediaTypes.`image/webp`), source))
          }

        case videoSegment(id, start, end) =>
          val segmentPath = api.resources.getVideoFragment(id, start.toLong, end.toLong)
          CustomDirectives.fileWithRangeSupport(segmentPath)

        case videoPattern(id) =>
          onSuccess(api.resources.getVideo(id)) {
            case None       => complete(StatusCodes.NotFound)
            case Some(path) => CustomDirectives.fileWithRangeSupport(path)
          }
        case _ =>
          val path = Path.of(s"${api.resources.resourcePath()}/$file")

          if (!path.toFile.exists())
            complete(StatusCodes.NotFound)
          else
            FileAndResourceDirectives.getFromFile(path.toFile)
      }
    }

  def webClientFiles: Route =
    rawPathPrefix(Slash) {

      extractUnmatchedPath { path =>

        // TODO sanitize
        val filePath = path.toString() match {
          case "" | "/" => "index.html"
          case other    => other
        }

        val targetFile = {
          val maybe = (File(config.webClientPath) / filePath)
          if (maybe.exists)
            maybe
          else
            File(config.webClientPath) / "index.html"
        }

        getFromFile(targetFile.path.toAbsolutePath.toString)
      }
    }
}

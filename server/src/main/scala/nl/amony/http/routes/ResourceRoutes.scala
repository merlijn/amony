package nl.amony.http.routes

import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.StreamConverters
import better.files.File
import nl.amony.http.RouteDeps
import nl.amony.http.util.CustomDirectives.fileWithRangeSupport
import scribe.Logging

trait ResourceRoutes extends Logging {

  self: RouteDeps =>

  object patterns {
    val Video = raw"(.+)\.mp4".r
    val VideoFragment = raw"(.+)~(\d+)-(\d+)\.mp4".r
    val Thumbnail = raw"(\w+)(-(\d+))?\.webp".r
  }

  val resourceRoutes =
    (get & path("files" / "resources" / Segment)) {

      case patterns.Thumbnail(id, _, timestamp) =>
        onSuccess(api.resources.getThumbnail(id, Option(timestamp).map(_.toLong))) {
          case None     => complete(StatusCodes.NotFound)
          case Some(is) =>
            val source = StreamConverters.fromInputStream(() => is, 8192)
            complete(HttpEntity(ContentType(MediaTypes.`image/webp`), source))
        }

      case patterns.VideoFragment(id, start, end) =>
        val segmentPath = api.resources.getVideoFragment(id, start.toLong, end.toLong)
        fileWithRangeSupport(segmentPath)

      case patterns.Video(id) =>
        onSuccess(api.resources.getVideo(id)) {
          case None => complete(StatusCodes.NotFound)
          case Some(path) => fileWithRangeSupport(path)
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

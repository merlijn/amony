package nl.amony.http.routes

import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.StreamConverters
import better.files.File
import nl.amony.http.RouteDeps
import nl.amony.http.util.HttpDirectives.{fileWithRangeSupport, uploadFiles}
import scribe.Logging

trait ResourceRoutes extends Logging {

  self: RouteDeps =>

  object patterns {
    // example: j0rc1048yc1_720p.mp4
    val Video         = raw"(.+)_(\d+)p\.mp4".r

    // example: j0rc1048yc1~1000-5000_720p.mp4
    val VideoFragment = raw"(.+)~(\d+)-(\d+)_(\d+)p\.mp4".r

    // example: j0rc1048yc1_720p.webp or j0rc1048yc1~5000_720p.webp
    val Thumbnail     = raw"(\w+)(-(\d+))?_(\d+)p\.webp".r
  }

  val resourceRoutes = {

    pathPrefix("files") {

      path("upload") {
        uploadFiles("video", api.config.media.uploadPath, config.uploadSizeLimit.toBytes.toLong) { f =>
          f.foreach { case (info, path) =>
            logger.info(s"$path was uploaded, scanning file")
            api.modify.addMediaFromLocalFile(path.toAbsolutePath)
          }
          complete("OK")
        }
      } ~ pathPrefix("resources") {

        (get & path(Segment)) {
          case patterns.Thumbnail(id, _, timestamp, quality) =>
            onSuccess(api.resources.getThumbnail(id, quality.toInt, Option(timestamp).map(_.toLong))) {
              case None => complete(StatusCodes.NotFound)
              case Some(is) =>
                val source = StreamConverters.fromInputStream(() => is, 8192)
                complete(HttpEntity(ContentType(MediaTypes.`image/webp`), source))
            }

          case patterns.VideoFragment(id, start, end, quality) =>
            val segmentPath = api.resources.getVideoFragment(id, quality.toInt, start.toLong, end.toLong)
            fileWithRangeSupport(segmentPath)

          case patterns.Video(id, quality) =>
            onSuccess(api.resources.getVideo(id)) {
              case None => complete(StatusCodes.NotFound)
              case Some(path) => fileWithRangeSupport(path)
            }

          case _ =>
            complete(StatusCodes.NotFound)
        }
      }
    }
  }

  def webAppResources: Route =
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

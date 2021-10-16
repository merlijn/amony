package nl.amony.http.routes

import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.directives.FileAndResourceDirectives
import akka.stream.scaladsl.StreamConverters
import better.files.File
import nl.amony.http.{CustomDirectives, RouteDeps, WebServerConfig}
import scribe.Logging

import java.nio.file.Path

trait ResourceRoutes extends Logging {

  self: RouteDeps =>

  val thumbnailRoutes =
    (get & path("files" / "thumbnails" / Segment) & parameters("t".optional)) { (file, timestamp) =>

      val split = file.split('.')
      val id = split(0)
      val ext = split(1)

      ext match {
        case "webp" =>
          onSuccess(api.resources.getThumbnail(id, timestamp.map(_.toLong))) {
            case None        => complete(StatusCodes.NotFound, "")
            case Some(is) =>
              val source = StreamConverters.fromInputStream(() => is, 8192)
              complete(HttpEntity(ContentType(MediaTypes.`image/webp`), source))
          }
        case "mp4" =>
          val videoPath = Path.of(api.resources.getVideoFragment(file))
          CustomDirectives.fileWithRangeSupport(videoPath)
        case _ =>
          val path = Path.of(s"${api.resources.resourcePath()}/${file}")

          if (!path.toFile.exists())
            complete(StatusCodes.NotFound)
          else
            FileAndResourceDirectives.getFromFile(path.toFile)
      }
    }

  val videoRoutes = path("files" / "videos" / Segment) { id =>
    onSuccess(api.query.getById(id)) {
      case None       => complete(StatusCodes.NotFound)
      case Some(info) =>
        val filePath = Path.of(api.resources.getFilePathForMedia(info))
        CustomDirectives.fileWithRangeSupport(filePath)
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

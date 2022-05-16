package nl.amony.http.routes

import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import nl.amony.WebServerConfig
import nl.amony.actor.resources.ResourceApi
import nl.amony.http.util.HttpDirectives.randomAccessRangeSupport
import nl.amony.http.util.HttpDirectives.uploadFiles
import scribe.Logging

object ResourceRoutes extends Logging {

  object patterns {
    // example: j0rc1048yc1_720p.mp4
    val Video = raw"(.+)_(\d+)p\.mp4".r

    // example: j0rc1048yc1~1000-5000_720p.mp4
    val VideoFragment = raw"(.+)~(\d+)-(\d+)_(\d+)p\.mp4".r

    // example: j0rc1048yc1_720p.webp or j0rc1048yc1~5000_720p.webp
    val Thumbnail = raw"(\w+)(-(\d+))?_(\d+)p\.webp".r
  }

  def apply(resourceApi: ResourceApi, config: WebServerConfig): Route = {

    implicit val timeout: Timeout = Timeout(config.requestTimeout)

    pathPrefix("files") {

      path("upload") {
        uploadFiles("video", config.uploadSizeLimit.toBytes.toLong) { (fileInfo, source) =>
          resourceApi.uploadMedia(fileInfo.fileName, source)
        } { medias => complete("OK") }
      } ~ pathPrefix("resources") {

        (get & path(Segment)) {
          case patterns.Thumbnail(id, _, timestamp, quality) =>
            onSuccess(resourceApi.getThumbnail(id, quality.toInt, Option(timestamp).map(_.toLong))) {
              case None => complete(StatusCodes.NotFound)
              case Some(ioResponse) =>
                complete(HttpEntity(ContentType(MediaTypes.`image/webp`), ioResponse.getContent()))
            }

          case patterns.VideoFragment(id, start, end, quality) =>
            onSuccess(resourceApi.getVideoFragment(id, start.toInt, end.toInt, quality.toInt)) {
              case None => complete(StatusCodes.NotFound)
              case Some(ioResponse) =>
                randomAccessRangeSupport(
                  ContentType(MediaTypes.`video/mp4`),
                  ioResponse.size(),
                  ioResponse.getContentRange
                )
            }

          case patterns.Video(id, quality) =>
            onSuccess(resourceApi.getVideo(id, quality.toInt)) {
              case None => complete(StatusCodes.NotFound)
              case Some(ioResponse) =>
                randomAccessRangeSupport(
                  ContentType(MediaTypes.`video/mp4`),
                  ioResponse.size(),
                  ioResponse.getContentRange
                )
            }

          case _ =>
            complete(StatusCodes.NotFound)
        }
      }
    }
  }
}

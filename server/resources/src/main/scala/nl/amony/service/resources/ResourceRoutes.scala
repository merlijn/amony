package nl.amony.service.resources

import cats.effect.IO
import nl.amony.lib.cats.FutureOps
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString
import scribe.Logging

object ResourceRoutes extends Logging {

  // format: off
  object patterns {
    // example: j0rc1048yc1_720p.mp4
    val Video = raw"(\w+)_(\d+)p\.mp4".r

    // example: j0rc1048yc1~1000-5000_720p.mp4
    val VideoFragment = raw"(.+)~(\d+)-(\d+)_(\d+)p\.mp4".r

    val Thumbnail = raw"(\w+)_(\d+)p\.webp".r

    val ThumbnailWithTimestamp = raw"(\w+)_(\d+)_(\d+)p\.webp".r

    // example: j0rc1048yc1-timeline.vtt
    val TimeLineVtt = raw"(\w+)-timeline\.vtt".r

    // example: j0rc1048yc1-timeline.webp
    val TimeLineJpeg = raw"(\w+)-timeline\.webp".r
  }
  // format: on

  def resourceResponse(req: Request[IO], ioResponse: IOResponse) =
    ResourceDirectives.responseWithRangeSupport[IO](
      req,
      ioResponse.size(),
      ioResponse.contentType().map(MediaType.parse(_).toOption).flatten,
      ioResponse.getContentRange
    )

  def apply(buckets: Map[String, ResourceBucket]) = {
    HttpRoutes.of[IO] {
      case req @ GET -> Root / "resources" / "media" / bucketId / resourceId =>

        resourceId match {

          case patterns.ThumbnailWithTimestamp(id, timestamp, quality) =>
            buckets(bucketId).getVideoThumbnail(id, quality.toInt, timestamp.toLong).toIO.flatMap {
              case None => NotFound()
              case Some(ioResponse) => Ok(ioResponse.getContent())
            }

          case patterns.Thumbnail(id, quality) =>
            buckets(bucketId).getImageThumbnail(id, quality.toInt).toIO.flatMap {
              case None             => NotFound()
              case Some(ioResponse) => Ok(ioResponse.getContent())
            }

          case patterns.VideoFragment(id, start, end, quality) =>
            buckets(bucketId).getVideoFragment(id, start.toLong, end.toLong, quality.toInt).toIO.flatMap {
              case None => NotFound()
              case Some(ioResponse) => resourceResponse(req, ioResponse)
            }

          case patterns.Video(id, quality) =>
            buckets(bucketId).getVideo(id, quality.toInt).toIO.flatMap {
              case None             => NotFound()
              case Some(ioResponse) => resourceResponse(req, ioResponse)
            }

          case _ =>
            buckets(bucketId).getResource(resourceId).toIO.flatMap {
              case None             => NotFound()
              case Some(ioResponse) => resourceResponse(req, ioResponse)
            }

          case _ =>
            NotFound()
        }
    }
  }
}

package nl.amony.service.resources

import cats.effect.IO
import nl.amony.lib.cats.FutureOps
import org.http4s._
import org.http4s.dsl.io._
import scribe.Logging

object ResourceRoutes extends Logging {

  // format: off
  object patterns {
    // example: j0rc1048yc1_720p.mp4
    val Video = raw"(.+)_(\d+)p\.mp4".r

    // example: j0rc1048yc1~1000-5000_720p.mp4
    val VideoFragment = raw"(.+)~(\d+)-(\d+)_(\d+)p\.mp4".r

    // example: j0rc1048yc1_720p.webp or j0rc1048yc1~5000_720p.webp
    val Thumbnail = raw"(\w+)(-(\d+))?_(\d+)p\.webp".r

    // example: j0rc1048yc1-timeline.vtt
    val TimeLineVtt = raw"(\w+)-timeline\.vtt".r

    // example: j0rc1048yc1-timeline.webp
    val TimeLineJpeg = raw"(\w+)-timeline\.webp".r
  }
  // format: on

  def videoResponse(req: Request[IO], ioResponse: IOResponse) =
    ResourceDirectives.rangedResponse[IO](
      req,
      ioResponse.size(),
      Some(MediaType.video.mp4),
      ioResponse.getContentRange
    )

  def apply(buckets: Map[String, ResourceBucket]) = {
    HttpRoutes.of[IO] {
      case req @ GET -> Root / "resources" / "media" / bucketId / resourceId => resourceId match {

        case patterns.Thumbnail(id, _, timestamp, quality) =>
          buckets(bucketId).getThumbnail(id, quality.toInt, timestamp.toLong).toIO.flatMap {
            case None             => NotFound()
            case Some(ioResponse) => Ok(ioResponse.getContent())
          }

        case patterns.VideoFragment(id, start, end, quality) =>
          buckets(bucketId).getVideoFragment(id, start.toLong, end.toLong, quality.toInt).toIO.flatMap {
            case None => NotFound()
            case Some(ioResponse) => videoResponse(req, ioResponse)
          }

        case patterns.Video(id, quality) =>
          buckets(bucketId).getResource(id, quality.toInt).toIO.flatMap {
            case None             => NotFound()
            case Some(ioResponse) => videoResponse(req, ioResponse)
          }
      }
    }
  }
}

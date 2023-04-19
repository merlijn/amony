package nl.amony.service.resources

import cats.effect.IO
import nl.amony.lib.cats.FutureOps
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString
import scribe.Logging

import scala.concurrent.Future

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

  private def respondWithResource(req: Request[IO], fn: => IO[Option[IOResponse]]): IO[Response[IO]] =
    fn.flatMap {
      case None             => NotFound()
      case Some(ioResponse) => ResourceDirectives.responseWithRangeSupport[IO](
        req,
        ioResponse.size(),
        ioResponse.contentType().map(MediaType.parse(_).toOption).flatten,
        ioResponse.getContentRange
      )
    }

  def apply(buckets: Map[String, ResourceBucket]): HttpRoutes[IO] = {
    HttpRoutes.of[IO] {
      case req @ GET -> Root / "resources" / bucketId / resourceId =>

        buckets.get(bucketId) match {
          case None => NotFound()
          case Some(bucket) =>
            resourceId match {

              case patterns.ThumbnailWithTimestamp(id, timestamp, quality) =>
                respondWithResource(req, bucket.getVideoThumbnail(id, quality.toInt, timestamp.toLong))

              case patterns.Thumbnail(id, quality) =>
                respondWithResource(req, bucket.getImageThumbnail(id, quality.toInt))

              case patterns.VideoFragment(id, start, end, quality) =>
                respondWithResource(req, bucket.getVideoFragment(id, start.toLong, end.toLong, quality.toInt))

              case patterns.Video(id, quality) =>
                respondWithResource(req, bucket.getVideoTranscode(id, quality.toInt))

              case _ =>
                respondWithResource(req, bucket.getResource(resourceId))
            }
        }
    }
  }
}

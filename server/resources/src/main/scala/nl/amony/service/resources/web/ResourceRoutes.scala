package nl.amony.service.resources.web

import cats.effect.IO
import nl.amony.service.resources.{ImageThumbnail, ResourceBucket, ResourceContent, VideoFragment, VideoThumbnail}
import org.http4s._
import org.http4s.dsl.io._
import scribe.Logging

object ResourceRoutes extends Logging {

  // format: off
  object patterns {
    val Video                  = raw"(\w+)(_(\d+)p)?\.mp4".r
    val VideoFragment          = raw"(.+)~(\d+)-(\d+)_(\d+)p\.mp4".r
    val Thumbnail              = raw"(\w+)_(\d+)p\.webp".r
    val ThumbnailWithTimestamp = raw"(\w+)_(\d+)_(\d+)p\.webp".r
  }
  // format: on

  private def respondWithResource(req: Request[IO], resourceContent: => IO[Option[ResourceContent]]): IO[Response[IO]] =
    resourceContent.flatMap {
      case None             =>
        NotFound()
      case Some(content) =>
        ResourceDirectives.responseWithRangeSupport[IO](
          req = req,
          size = content.size(),
          maybeMediaType = content.contentType().map(MediaType.parse(_).toOption).flatten,
          rangeResponseFn = content.getContentRange
        )
    }

  def apply(buckets: Map[String, ResourceBucket]): HttpRoutes[IO] = {
    HttpRoutes.of[IO] {
      case req @ GET -> Root / "resources" / bucketId / resourceId =>

        buckets.get(bucketId) match {
          case None =>
            NotFound()
          case Some(bucket) =>
            resourceId match {
              case patterns.ThumbnailWithTimestamp(id, timestamp, quality) => respondWithResource(req, bucket.getOrCreate(id, VideoThumbnail(timestamp.toLong, quality.toInt)))
              case patterns.Thumbnail(id, scaleHeight)                     => respondWithResource(req, bucket.getOrCreate(id, ImageThumbnail(scaleHeight.toInt)))
              case patterns.VideoFragment(id, start, end, quality)         => respondWithResource(req, bucket.getOrCreate(id, VideoFragment(start.toLong, end.toLong, quality.toInt)))
              case patterns.Video(id, _, null)                             => respondWithResource(req, bucket.getContent(id))
              case _                                                       => respondWithResource(req, bucket.getContent(resourceId))
            }
        }
    }
  }
}

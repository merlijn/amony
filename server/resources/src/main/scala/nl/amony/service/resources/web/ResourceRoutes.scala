package nl.amony.service.resources.web

import cats.effect.IO
import nl.amony.service.resources.api.operations.{ImageThumbnail, VideoFragment, VideoThumbnail}
import nl.amony.service.resources.{ResourceBucket, ResourceContent}
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

  def apply(buckets: Map[String, ResourceBucket]): HttpRoutes[IO] = {
    HttpRoutes.of[IO] {
      case req @ GET -> Root / "resources" / bucketId / resourcePattern =>

        buckets.get(bucketId) match {
          case None =>
            NotFound()
          case Some(bucket) =>
            val maybeResource: IO[Option[ResourceContent]] =
              resourcePattern match {
                case patterns.ThumbnailWithTimestamp(id, timestamp, height) => bucket.getOrCreate(id, VideoThumbnail(width = None, height = Some(height.toInt), 23, timestamp.toLong), Set.empty)
                case patterns.Thumbnail(id, scaleHeight)                    => bucket.getOrCreate(id, ImageThumbnail(width = None, height = Some(scaleHeight.toInt), 0), Set.empty)
                case patterns.VideoFragment(id, start, end, height)         => bucket.getOrCreate(id, VideoFragment(width = None, height = Some(height.toInt), start.toLong, end.toLong, 23), Set.empty)
                case patterns.Video(id, _, null)                            => bucket.getResource(id)
                case id                                                     => bucket.getResource(id)
              }

            maybeResource.flatMap {
              case None =>
                NotFound()
              case Some(content) =>
                ResourceDirectives.responseWithRangeSupport[IO](
                  req = req,
                  size = content.size(),
                  maybeMediaType = content.contentType().map(MediaType.parse(_).toOption).flatten,
                  rangeResponseFn = content.getContentRange
                )
            }
        }
    }
  }
}

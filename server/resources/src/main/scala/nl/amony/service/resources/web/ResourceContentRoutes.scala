package nl.amony.service.resources.web

import cats.effect.IO
import nl.amony.service.resources.api.operations.{ImageThumbnail, ResourceOperation, VideoFragment, VideoThumbnail}
import nl.amony.service.resources.web.ResourceDirectives.responseFromResource
import nl.amony.service.resources.{Resource, ResourceBucket}
import org.http4s.*
import org.http4s.CacheDirective.`max-age`
import org.http4s.dsl.io.*
import org.http4s.headers.`Cache-Control`
import scribe.Logging

import scala.concurrent.duration.DurationInt

object ResourceContentRoutes extends Logging {

  object patterns {
    val ClipPattern                   = raw"clip_(\d+)-(\d+)_(\d+)p\.mp4".r
    val ThumbnailPattern              = raw"thumb_(\d+)p\.webp".r
    val ThumbnailWithTimestampPattern = raw"thumb_(\d+)_(\d+)p\.webp".r

    val matchPF: PartialFunction[String, ResourceOperation] = {
      case patterns.ThumbnailWithTimestampPattern(timestamp, height) => VideoThumbnail(width = None, height = Some(height.toInt), 23, timestamp.toLong)
      case patterns.ThumbnailPattern(scaleHeight) => ImageThumbnail(width = None, height = Some(scaleHeight.toInt), 0)
      case patterns.ClipPattern(start, end, height) => VideoFragment(width = None, height = Some(height.toInt), start.toLong, end.toLong, 23)
    }
  }

  def apply(buckets: Map[String, ResourceBucket]): HttpRoutes[IO] = {

    def withResource(bucketId: String, resourceId: String)(fn: (ResourceBucket, Resource) => IO[Response[IO]]) =
      buckets.get(bucketId) match
        case None         => NotFound()
        case Some(bucket) =>
          bucket.getResource(resourceId).flatMap:
            case None => NotFound()
            case Some(resource) => fn(bucket, resource)

    HttpRoutes.of[IO] {

      case req @ GET -> Root / "api" / "resources" / bucketId / resourceId / "content" =>

        withResource(bucketId, resourceId) { (_, resource) =>
          responseFromResource(req, resource)
        }

      case req @ GET -> Root / "api" / "resources" / bucketId / resourceId / resourcePattern =>

        withResource(bucketId, resourceId) { (bucket, resource) =>
          patterns.matchPF.lift(resourcePattern) match
            case None            => NotFound()
            case Some(operation) =>
              bucket.getOrCreate(resourceId, operation).flatMap:
                case None           => NotFound()
                case Some(resource) =>
                  responseFromResource(req, resource).map {
                    r => r.addHeader(`Cache-Control`(`max-age`(365.days)))
                  }
        }
    }
  }
}

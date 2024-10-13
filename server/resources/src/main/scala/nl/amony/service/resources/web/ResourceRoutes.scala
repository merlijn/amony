package nl.amony.service.resources.web

import cats.Monad
import cats.effect.IO
import nl.amony.service.resources.api.operations.{ImageThumbnail, VideoFragment, VideoThumbnail}
import nl.amony.service.resources.{ResourceBucket, Resource}
import nl.amony.service.resources.web.JsonCodecs.given
import org.http4s.*
import org.http4s.dsl.io.*
import scribe.Logging
import io.circe.syntax.*
import nl.amony.service.resources.web.ResourceWebModel.UserMetaDto
import org.http4s.circe.*
import org.http4s.circe.CirceEntityDecoder.circeEntityDecoder

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

    def withBucket[F[_]: Monad](bucketId: String)(fn: ResourceBucket => F[Response[F]]) =
      buckets.get(bucketId) match
        case None         => NotFound()
        case Some(bucket) => fn(bucket)

    HttpRoutes.of[IO] {

      case req @ GET -> Root / "api" / "resources" / bucketId / resourceId =>

        withBucket(bucketId) { bucket =>
          bucket.getResource(resourceId).flatMap:
            case None           => NotFound()
            case Some(resource) => Ok(resource.info().asJson)
        }

      case req @ POST -> Root / "api" / "resources" / bucketId / resourceId / "update_user_meta" =>

        withBucket(bucketId) { bucket =>
          bucket.getResource(resourceId).flatMap: 
            case None => NotFound()
            case Some(resource) =>
              req.as[UserMetaDto].flatMap { userMeta =>
                bucket.updateUserMeta(resourceId, userMeta.title, userMeta.description, userMeta.tags).flatMap(_ => Ok())
              }
          
        }

      case req @ GET -> Root / "resources" / bucketId / resourcePattern =>

        withBucket(bucketId) { bucket =>
          val maybeResource: IO[Option[Resource]] =
            resourcePattern match
              case patterns.ThumbnailWithTimestamp(id, timestamp, height) => bucket.getOrCreate(id, VideoThumbnail(width = None, height = Some(height.toInt), 23, timestamp.toLong), Set.empty)
              case patterns.Thumbnail(id, scaleHeight) => bucket.getOrCreate(id, ImageThumbnail(width = None, height = Some(scaleHeight.toInt), 0), Set.empty)
              case patterns.VideoFragment(id, start, end, height) => bucket.getOrCreate(id, VideoFragment(width = None, height = Some(height.toInt), start.toLong, end.toLong, 23), Set.empty)
              case patterns.Video(id, _, null) => bucket.getResource(id)
              case id => bucket.getResource(id)

          maybeResource.flatMap:
            case None =>
              NotFound()
            case Some(content) =>
              ResourceDirectives.responseWithRangeSupport[IO](
                request = req,
                size = content.size(),
                maybeMediaType = content.contentType().map(MediaType.parse(_).toOption).flatten,
                rangeResponseFn = content.getContentRange
              )
        }
    }
  }
}

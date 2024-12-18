package nl.amony.service.resources.web

import cats.Monad
import cats.effect.IO
import nl.amony.service.resources.api.operations.{ImageThumbnail, VideoFragment, VideoThumbnail}
import nl.amony.service.resources.{Resource, ResourceBucket, ResourceWithRangeSupport}
import nl.amony.service.resources.web.JsonCodecs.given
import org.http4s.*
import org.http4s.dsl.io.*
import scribe.Logging
import io.circe.syntax.*
import nl.amony.service.resources.web.ResourceWebModel.{ThumbnailTimestampDto, UserMetaDto}
import org.http4s.circe.*
import org.http4s.circe.CirceEntityDecoder.circeEntityDecoder
import org.http4s.headers.`Content-Type`

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

      case req @ POST -> Root / "api" / "resources" / bucketId / resourceId / "update_thumbnail_timestamp" =>

        withBucket(bucketId) { bucket =>
          bucket.getResource(resourceId).flatMap:
            case None => NotFound()
            case Some(_) =>
            req.as[ThumbnailTimestampDto].flatMap { dto =>
              bucket.updateThumbnailTimestamp(resourceId, dto.timestampInMillis).flatMap(_ => Ok())
            }
        }

      case req @ POST -> Root / "api" / "resources" / bucketId / resourceId / "update_user_meta" =>

        withBucket(bucketId) { bucket =>
          bucket.getResource(resourceId).flatMap: 
            case None => NotFound()
            case Some(_) =>
              req.as[UserMetaDto].flatMap { userMeta =>
                bucket.updateUserMeta(resourceId, userMeta.title, userMeta.description, userMeta.tags).flatMap(_ => Ok())
              }
          
        }

      case req @ GET -> Root / "resources" / bucketId / resourcePattern =>

        withBucket(bucketId) { bucket =>
          val maybeResource: IO[Option[Resource]] =
            resourcePattern match
              case patterns.ThumbnailWithTimestamp(id, timestamp, height) => bucket.getOrCreate(id, VideoThumbnail(width = None, height = Some(height.toInt), 23, timestamp.toLong))
              case patterns.Thumbnail(id, scaleHeight)                    => bucket.getOrCreate(id, ImageThumbnail(width = None, height = Some(scaleHeight.toInt), 0))
              case patterns.VideoFragment(id, start, end, height)         => bucket.getOrCreate(id, VideoFragment(width = None, height = Some(height.toInt), start.toLong, end.toLong, 23))
              case patterns.Video(id, _, null)                            => bucket.getResource(id)
              case id                                                     => bucket.getResource(id)

          maybeResource.flatMap:
            case None =>
              NotFound()
            case Some(resource: ResourceWithRangeSupport) =>
              ResourceDirectives.responseWithRangeSupport[IO](
                request = req,
                size = resource.size(),
                maybeMediaType = resource.contentType().map(MediaType.parse(_).toOption).flatten,
                rangeResponseFn = resource.getContentRange
              )
            case Some(resource) =>

              val maybeMediaType = resource.contentType().map(MediaType.parse(_).toOption).flatten.map(`Content-Type`.apply)

              Response(
                status = Status.Ok,
                headers = maybeMediaType.map(mediaType => Headers(mediaType)).getOrElse(Headers.empty),
                entity = Entity.stream(resource.getContent())
              )

              Ok(resource.getContent())
        }
    }
  }
}

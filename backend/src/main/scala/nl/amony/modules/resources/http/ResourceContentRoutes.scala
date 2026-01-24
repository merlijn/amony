package nl.amony.modules.resources.http

import scala.concurrent.duration.DurationInt

import cats.data.{EitherT, OptionT}
import cats.effect.IO
import io.circe.syntax.*
import org.http4s.*
import org.http4s.CacheDirective.`max-age`
import org.http4s.circe.given
import org.http4s.dsl.io.*
import org.http4s.headers.`Cache-Control`
import scribe.Logging
import sttp.model.StatusCode

import nl.amony.lib.tapir.ErrorResponse
import nl.amony.modules.auth.api.{ApiSecurity, SecurityError}
import nl.amony.modules.resources.api.*
import nl.amony.modules.resources.http.ResourceDirectives.resourceContentsResponse

object ResourceContentRoutes extends Logging {

  object patterns {
    val ClipPattern                   = raw"clip_(\d+)-(\d+)_(\d+)p\.mp4".r
    val ThumbnailPattern              = raw"thumb_(\d+)p\.webp".r
    val ThumbnailWithTimestampPattern = raw"thumb_(\d+)_(\d+)p\.webp".r

    val resolutions = List(120, 240, 320, 480, 640, 1024, 1920, 2160, 4320)

    val matchPF: PartialFunction[String, ResourceOperation] = {
      case patterns.ThumbnailWithTimestampPattern(timestamp, height) =>
        VideoThumbnail(width = None, height = Some(height.toInt), 23, timestamp.toLong)
      case patterns.ThumbnailPattern(scaleHeight)                    => ImageThumbnail(width = None, height = Some(scaleHeight.toInt), 0)
      case patterns.ClipPattern(start, end, height)                  => VideoFragment(width = None, height = Some(height.toInt), start.toLong, end.toLong, 23)
    }
  }

  def apply(buckets: Map[String, ResourceBucket], apiSecurity: ApiSecurity): HttpRoutes[IO] = {

    def getResource(bucketId: String, resourceId: ResourceId): OptionT[IO, (ResourceBucket, Resource)] =
      for
        bucket   <- OptionT.fromOption[IO](buckets.get(bucketId))
        resource <- OptionT(bucket.getResource(resourceId))
      yield bucket -> resource

    def maybeResponse(option: OptionT[IO, Response[IO]]): IO[Response[IO]] = option.value.map(_.getOrElse(Response(Status.NotFound)))

    def response(option: EitherT[IO, ErrorResponse, Response[IO]]): IO[Response[IO]] = option.value.map {
      case Left(ErrorResponse(statusCode, _)) => Response(Status.apply(statusCode.code))
      case Right(response)                    => response
    }

    def mapSecurityError(securityError: SecurityError): ErrorResponse =
      securityError match
        case SecurityError.Unauthorized => ErrorResponse.unauthorized()
        case SecurityError.Forbidden    => ErrorResponse.forbidden()

    def mapUploadError(uploadError: UploadError): ErrorResponse =
      uploadError match
        case UploadError.InvalidFileName(_) => ErrorResponse.notFound("bucket_not_found", "Bucket not found")
        case UploadError.StorageError(_)    => ErrorResponse.badRequest("invalid_content_type", "Invalid content type")

    HttpRoutes.of[IO] {

      // TODO: rewrite upload to tapir
      case req @ POST -> Root / "api" / "resources" / bucketId / "upload" =>
        response {
          for
            session  <- EitherT.fromEither[IO](apiSecurity.requireSession(req)).leftMap(mapSecurityError)
            bucket   <- EitherT.fromOption[IO](buckets.get(bucketId), ErrorResponse.notFound("bucket_not_found", s"Bucket '$bucketId' not found"))
            resource <- EitherT(bucket.uploadResource(session.userId, "test", req.body)).leftMap(mapUploadError)
            response <- EitherT.liftF(Ok(toDto(resource).asJson))
          yield response
        }

      case req @ GET -> Root / "api" / "resources" / bucketId / resourceId / "content" =>
        maybeResponse:
          getResource(bucketId, ResourceId(resourceId)).semiflatMap((_, resource) => resourceContentsResponse(req, resource.content))

      case req @ GET -> Root / "api" / "resources" / bucketId / resourceId / resourcePattern =>
        maybeResponse:
          for
            (bucket, resource) <- getResource(bucketId, ResourceId(resourceId))
            operation          <- OptionT.fromOption(patterns.matchPF.lift(resourcePattern))
            derivedResource    <- OptionT(bucket.getOrCreate(ResourceId(resourceId), operation))
            response           <- OptionT.liftF(resourceContentsResponse(req, derivedResource).map(r => r.addHeader(`Cache-Control`(`max-age`(365.days)))))
          yield response
    }
  }
}

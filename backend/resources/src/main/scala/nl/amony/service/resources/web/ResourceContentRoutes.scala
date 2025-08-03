package nl.amony.service.resources.web

import cats.data.OptionT
import cats.effect.IO
import nl.amony.service.resources.api.operations.{ImageThumbnail, ResourceOperation, VideoFragment, VideoThumbnail}
import nl.amony.service.resources.web.ResourceDirectives.responseFromResource
import nl.amony.service.resources.{Resource, ResourceBucket}
import org.http4s.*
import org.http4s.CacheDirective.`max-age`
import org.http4s.dsl.io.*
import org.http4s.headers.`Cache-Control`
import scribe.Logging
import io.circe.syntax.*
import org.http4s.circe.given
import nl.amony.service.resources.web.dto.toDto

import scala.concurrent.duration.DurationInt

object ResourceContentRoutes extends Logging {

  object patterns {
    val ClipPattern                   = raw"clip_(\d+)-(\d+)_(\d+)p\.mp4".r
    val ThumbnailPattern              = raw"thumb_(\d+)p\.webp".r
    val ThumbnailWithTimestampPattern = raw"thumb_(\d+)_(\d+)p\.webp".r

    val matchPF: PartialFunction[String, ResourceOperation] = {
      case patterns.ThumbnailWithTimestampPattern(timestamp, height) => VideoThumbnail(width = None, height = Some(height.toInt), 23, timestamp.toLong)
      case patterns.ThumbnailPattern(scaleHeight)                    => ImageThumbnail(width = None, height = Some(scaleHeight.toInt), 0)
      case patterns.ClipPattern(start, end, height)                  => VideoFragment(width = None, height = Some(height.toInt), start.toLong, end.toLong, 23)
    }
  }

  def apply(buckets: Map[String, ResourceBucket]): HttpRoutes[IO] = {

    def getResource(bucketId: String, resourceId: String): OptionT[IO, (ResourceBucket, Resource)] =
      for {
        bucket   <- OptionT.fromOption[IO](buckets.get(bucketId))
        resource <- OptionT(bucket.getResource(resourceId))
      } yield bucket -> resource
   
    def maybeResponse(option: OptionT[IO, Response[IO]]): IO[Response[IO]] =
     option.value.map(_.getOrElse(Response(Status.NotFound)))

    HttpRoutes.of[IO] {

      case req @ POST -> Root / "api" / "resources" / bucketId / "upload" =>
        maybeResponse:
          for {
            bucket   <- OptionT.fromOption[IO](buckets.get(bucketId))
            resource <- OptionT.liftF(bucket.uploadResource("0", "test", req.body))
            response <- OptionT.liftF(Ok(toDto(resource).asJson))
          } yield response

      case req @ GET -> Root / "api" / "resources" / bucketId / resourceId / "content" =>
        maybeResponse:
          getResource(bucketId, resourceId).semiflatMap { (_, resource) => responseFromResource(req, resource) }

      case req @ GET -> Root / "api" / "resources" / bucketId / resourceId / resourcePattern =>
        maybeResponse:
          for {
            (bucket, resource) <- getResource(bucketId, resourceId)
            operation          <- OptionT.fromOption(patterns.matchPF.lift(resourcePattern))
            derivedResource    <- OptionT(bucket.getOrCreate(resourceId, operation))
            response           <- OptionT.liftF(responseFromResource(req, derivedResource).map(r => r.addHeader(`Cache-Control`(`max-age`(365.days)))))
          } yield response
    }
  }
}

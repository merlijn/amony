package nl.amony.modules.resources.http

import scala.concurrent.duration.DurationInt

import cats.data.OptionT
import cats.effect.IO
import org.http4s.*
import org.http4s.CacheDirective.`max-age`
import org.http4s.dsl.io.*
import org.http4s.headers.`Cache-Control`
import scribe.Logging

import nl.amony.modules.auth.api.ApiSecurity
import nl.amony.modules.resources.api.*
import nl.amony.modules.resources.http.ResourceDirectives.resourceContentsResponse

object ResourceContentRoutes extends Logging {

  object patterns {

    // Internal patterns used by ResourceOperation (allow specific parameters)
    val ClipPattern                   = raw"clip_(\d+)-(\d+)_(\d+)p\.mp4".r
    val ThumbnailPattern              = raw"thumb_(\d+)p\.webp".r
    val ThumbnailWithTimestampPattern = raw"thumb_(\d+)_(\d+)p\.webp".r

    val resolutionsMap = Map(
      "xxs" -> 120,
      "xs"  -> 240,
      "s"   -> 320,
      "m"   -> 480,
      "l"   -> 1024,
      "xl"  -> 1920,
      "xxl" -> 4320
    )

    val defaultResolution = "s"

    // Public patterns use a resolution key (e.g. "s", "m") instead of arbitrary pixel heights
    val PublicThumbnailPattern = raw"thumb_([a-z]+)\.webp".r
    val PublicClipPattern      = raw"clip_([a-z]+)\.mp4".r

    /** Derives a thumbnail operation from a resource, ignoring user-supplied timestamp/resolution. */
    def thumbnailOperation(resolutionKey: String, resource: ResourceInfo): Option[ResourceOperation] = {
      val height = resolutionsMap.getOrElse(resolutionKey, resolutionsMap(defaultResolution))
      resource.basicContentProperties match {
        case Some(video: VideoProperties) =>
          val timestamp = resource.thumbnailTimestamp.getOrElse(video.durationInMillis / 3).toLong
          Some(VideoThumbnail(width = None, height = Some(height), quality = 23, timestamp = timestamp))
        case Some(_: ImageProperties) =>
          Some(ImageThumbnail(width = None, height = Some(height), quality = 0))
        case _ =>
          None
      }
    }

    /** Derives a preview clip operation from a resource, ignoring user-supplied timestamps/resolution. */
    def clipOperation(resolutionKey: String, resource: ResourceInfo): Option[ResourceOperation] = {
      val height = resolutionsMap.getOrElse(resolutionKey, resolutionsMap(defaultResolution))
      resource.basicContentProperties match {
        case Some(video: VideoProperties) =>
          val start = resource.thumbnailTimestamp.getOrElse(video.durationInMillis / 3).toLong
          val end   = Math.min(video.durationInMillis.toLong, start + 3000L)
          Some(VideoFragment(width = None, height = Some(height), start = start, end = end, quality = 23))
        case _ =>
          None
      }
    }
  }

  def apply(buckets: Map[String, ResourceBucket], apiSecurity: ApiSecurity): HttpRoutes[IO] = {

    def getResource(bucketId: String, resourceId: ResourceId): OptionT[IO, (ResourceBucket, Resource)] =
      for
        bucket   <- OptionT.fromOption[IO](buckets.get(bucketId))
        resource <- OptionT(bucket.getResource(resourceId))
      yield bucket -> resource

    def maybeResponse(option: OptionT[IO, Response[IO]]): IO[Response[IO]] =
      option.value.map(_.getOrElse(Response(Status.NotFound)))

    HttpRoutes.of[IO] {

      case req @ GET -> Root / "api" / "resources" / bucketId / resourceId / "content" =>
        maybeResponse:
          getResource(bucketId, ResourceId(resourceId)).semiflatMap((_, resource) => resourceContentsResponse(req, resource.content))

      case req @ GET -> Root / "api" / "resources" / bucketId / resourceId / resourcePattern =>
        maybeResponse(
          for
            (bucket, resource)  <- getResource(bucketId, ResourceId(resourceId))
            operation           <- OptionT.fromOption(resourcePattern match {
                                     case patterns.PublicThumbnailPattern(resKey) => patterns.thumbnailOperation(resKey, resource.info)
                                     case patterns.PublicClipPattern(resKey)      => patterns.clipOperation(resKey, resource.info)
                                     case _                                       => None
                                   })
            derivedResource     <- OptionT(bucket.getOrCreate(ResourceId(resourceId), operation))
            response            <- OptionT.liftF(resourceContentsResponse(req, derivedResource)
                                     .map(r => r.addHeader(`Cache-Control`(`max-age`(365.days)))))
          yield response
        )
    }
  }
}

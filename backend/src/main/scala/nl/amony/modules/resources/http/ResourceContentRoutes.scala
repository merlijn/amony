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

    val resolutionsMap = Map(
      "xxs" -> 144,
      "xs"  -> 240,
      "s"   -> 352,
      "m"   -> 512,
      "l"   -> 1080, // FHD
      "xl"  -> 2160, // 4k
      "xxl" -> 4320  // 8k
    )

    val defaultResolution = "s"

    // Public patterns: timestamp for cache-busting + resolution key (e.g. "s", "m")
    // thumb_{timestamp}_{resKey}.webp  — videos and images
    // clip_{timestamp}_{resKey}.mp4    — videos only
    val PublicThumbnailPattern = raw"thumb_(\d+)_([a-z]+)\.webp".r
    val PublicClipPattern      = raw"clip_(\d+)_([a-z]+)\.mp4".r

    /**
     * Derives the canonical thumbnail timestamp for a resource:
     * uses the stored thumbnailTimestamp, or falls back to durationInMillis / 3.
     */
    def canonicalTimestamp(resource: ResourceInfo): Long =
      resource.basicContentProperties match {
        case Some(video: VideoProperties) =>
          resource.thumbnailTimestamp.getOrElse(video.durationInMillis / 3).toLong
        case _                            => 0L
      }

    /**
     * Builds a thumbnail operation only when the URL timestamp matches the resource's
     * canonical timestamp, preventing arbitrary timestamp injection.
     */
    def thumbnailOperation(urlTimestamp: Long, resolutionKey: String, resource: ResourceInfo): Option[ResourceOperation] = {
      val height = resolutionsMap.getOrElse(resolutionKey, resolutionsMap(defaultResolution))
      resource.basicContentProperties match {
        case Some(video: VideoProperties) =>
          val ts = resource.thumbnailTimestamp.getOrElse(video.durationInMillis / 3).toLong
          if urlTimestamp == ts then Some(VideoThumbnail(width = None, height = Some(height), quality = 23, timestamp = ts))
          else None
        case Some(_: ImageProperties)     =>
          // Images have no meaningful timestamp; accept any value (timestamp is only for cache-busting)
          Some(ImageThumbnail(width = None, height = Some(height), quality = 0))
        case _                            =>
          None
      }
    }

    /**
     * Builds a clip operation only when the URL timestamp matches the resource's
     * canonical timestamp, preventing arbitrary start/end injection.
     */
    def clipOperation(urlTimestamp: Long, resolutionKey: String, resource: ResourceInfo): Option[ResourceOperation] = {
      val height = resolutionsMap.getOrElse(resolutionKey, resolutionsMap(defaultResolution))
      resource.basicContentProperties match {
        case Some(video: VideoProperties) =>
          val start = resource.thumbnailTimestamp.getOrElse(video.durationInMillis / 3).toLong
          if urlTimestamp == start then
            val end = Math.min(video.durationInMillis.toLong, start + 3000L)
            Some(VideoFragment(width = None, height = Some(height), start = start, end = end, quality = 23))
          else None
        case _                            =>
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
            (bucket, resource) <- getResource(bucketId, ResourceId(resourceId))
            operation          <- OptionT.fromOption(resourcePattern match {
                                    case patterns.PublicThumbnailPattern(ts, resKey) => patterns.thumbnailOperation(ts.toLong, resKey, resource.info)
                                    case patterns.PublicClipPattern(ts, resKey)      => patterns.clipOperation(ts.toLong, resKey, resource.info)
                                    case _                                           => None
                                  })
            derivedResource    <- OptionT(bucket.getOrCreate(ResourceId(resourceId), operation))
            response           <- OptionT.liftF(resourceContentsResponse(req, derivedResource)
                                    .map(r => r.addHeader(`Cache-Control`(`max-age`(365.days)))))
          yield response
        )
    }
  }
}

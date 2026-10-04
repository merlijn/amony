package nl.amony.modules.resources.http

import scala.concurrent.duration.DurationInt

import cats.data.OptionT
import cats.effect.IO
import org.http4s.*
import org.http4s.CacheDirective.`max-age`
import org.http4s.dsl.io.*
import org.http4s.headers.`Cache-Control`
import scribe.Logging

import nl.amony.modules.auth.api.{ApiSecurity, authCookieName}
import nl.amony.modules.resources.api.*
import nl.amony.modules.resources.http.ResourceDirectives.resourceContentsResponse

object ResourceContentRoutes extends Logging {

  object patterns {

    // Public URL patterns: timestamp for cache-busting + pinned dimension + resolution size in pixels
    // + image format extension.
    // thumb_{timestamp}_{dim}_{size}.{format}  e.g. thumb_2863_w_768.avif       (videos and images)
    // clip_{start}_{end}_{dim}_{size}.mp4      e.g. clip_2863_5863_w_512.mp4    (videos only)
    val PublicThumbnailPattern = raw"thumb_(\d+)_([wh])_(\d+)\.([a-z0-9]+)".r
    val PublicClipPattern      = raw"clip_(\d+)_(\d+)_([wh])_(\d+)\.mp4".r

    /**
     * Builds a thumbnail operation only when the URL timestamp matches the resource's
     * canonical timestamp, preventing arbitrary timestamp injection.
     */
    def thumbnailOperation(
      urlTimestamp: Long,
      dimensionToken: String,
      resolutionKey: String,
      formatName: String,
      resolutions: ThumbnailResolutions,
      formats: ThumbnailFormats,
      resource: ResourceInfo
    ): Option[ResourceOperation] = {
      val requestedSize       = resolutionKey.toIntOption.getOrElse(resolutions.default)
      val (dimension, pixels) = resolutions.resolve(dimensionToken, requestedSize)
      val format              = formats.resolve(formatName)
      val source              = ResourceOperations.sourceDimensions(resource)
      resource.basicContentProperties match {
        case Some(video: VideoProperties) =>
          val ts = resource.thumbnailTimestamp.getOrElse(video.durationInMillis / 3).toLong
          if urlTimestamp == ts then
            val (width, height) =
              ResourceOperations.scaledDimensions(pixels, dimension, source, even = true, minimumDimension = format.minimumDimension)
            Some(VideoThumbnail(width = width, height = height, timestamp = ts, format = format))
          else None
        case Some(_: ImageProperties)     =>
          val (width, height) =
            ResourceOperations.scaledDimensions(pixels, dimension, source, even = false, minimumDimension = format.minimumDimension)
          Some(ImageThumbnail(width = width, height = height, format = format))
        case _                            => None
      }
    }

    /**
     * Builds a clip operation only when the URL start and end match the resource's canonical preview
     * range, preventing arbitrary range injection.
     */
    def clipOperation(
      urlStart: Long,
      urlEnd: Long,
      dimensionToken: String,
      resolutionKey: String,
      resolutions: ThumbnailResolutions,
      resource: ResourceInfo
    ): Option[ResourceOperation] = {
      val requestedSize       = resolutionKey.toIntOption.getOrElse(resolutions.default)
      val (dimension, pixels) = resolutions.resolve(dimensionToken, requestedSize)
      val source              = ResourceOperations.sourceDimensions(resource)
      resource.basicContentProperties match {
        case Some(video: VideoProperties) =>
          val (start, end) = VideoFragment.previewRange(resource.thumbnailTimestamp, video.durationInMillis)
          if urlStart == start && urlEnd == end then
            val (width, height) = ResourceOperations.scaledDimensions(pixels, dimension, source, even = true)
            Some(VideoFragment(width = width, height = height, start = start, end = end))
          else None
        case _                            =>
          None
      }
    }
  }

  def apply(buckets: Map[BucketId, ResourceBucket], resolutions: ThumbnailResolutions, formats: ThumbnailFormats)(using
    apiSecurity: ApiSecurity): HttpRoutes[IO] = {

    // The content routes are not Tapir endpoints, so the access token has to be read from the cookie directly.
    def authToken(req: Request[IO]) =
      val accessToken = req.cookies.find(_.name == authCookieName).map(_.content)
      apiSecurity.decodeAccessToken(accessToken)

    def isAnonymouslyForbidden(req: Request[IO]): Boolean =
      apiSecurity.isLoginRequired && authToken(req).isAnonymous

    def getResource(req: Request[IO], bucketId: BucketId, resourceId: ResourceId): OptionT[IO, (ResourceBucket, Resource)] =
      OptionT.fromOption[IO](buckets.get(bucketId))
        .filter(bucket => apiSecurity.canAccessBucket(authToken(req), bucket.requiredRole))
        .flatMap(bucket => OptionT(bucket.getResource(resourceId)).map(resource => bucket -> resource))

    def maybeResponse(option: OptionT[IO, Response[IO]]): IO[Response[IO]] =
      option.value.map(_.getOrElse(Response(Status.NotFound)))

    HttpRoutes.of[IO] {

      case req @ GET -> Root / "api" / "resources" / bucketId / resourceId / "content" =>
        if isAnonymouslyForbidden(req) then IO.pure(Response(Status.Unauthorized))
        else
          maybeResponse:
            getResource(req, BucketId(bucketId), ResourceId(resourceId)).semiflatMap((_, resource) => resourceContentsResponse(req, resource.content))

      case req @ GET -> Root / "api" / "resources" / bucketId / resourceId / resourcePattern =>
        if isAnonymouslyForbidden(req) then IO.pure(Response(Status.Unauthorized))
        else
          maybeResponse(
            for
              (bucket, resource) <- getResource(req, BucketId(bucketId), ResourceId(resourceId))
              operation          <- OptionT.fromOption(resourcePattern match {
                                      case patterns.PublicThumbnailPattern(ts, dim, resKey, format) =>
                                        patterns.thumbnailOperation(ts.toLong, dim, resKey, format, resolutions, formats, resource.info)
                                      case patterns.PublicClipPattern(start, end, dim, resKey)      =>
                                        patterns.clipOperation(start.toLong, end.toLong, dim, resKey, resolutions, resource.info)
                                      case _                                                        => None
                                    })
              derivedResource    <- OptionT(bucket.getOrCreate(ResourceId(resourceId), operation))
              response           <- OptionT.liftF(resourceContentsResponse(req, derivedResource)
                                      .map(r => r.addHeader(`Cache-Control`(`max-age`(365.days)))))
            yield response
          )
    }
  }
}

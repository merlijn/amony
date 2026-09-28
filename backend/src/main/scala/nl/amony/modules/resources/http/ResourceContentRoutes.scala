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
    // thumb_{timestamp}_{dim}_{size}.{format}  e.g. thumb_2863_w_768.avif  (videos and images)
    // clip_{timestamp}_{dim}_{size}.mp4        e.g. clip_2863_h_512.mp4    (videos only)
    val PublicThumbnailPattern = raw"thumb_(\d+)_([wh])_(\d+)\.([a-z0-9]+)".r
    val PublicClipPattern      = raw"clip_(\d+)_([wh])_(\d+)\.mp4".r

    /** The resource's native pixel dimensions (0, 0 when unknown). */
    private def sourceDimensions(resource: ResourceInfo): (Int, Int) = resource.basicContentProperties match {
      case Some(video: VideoProperties) => (video.width, video.height)
      case Some(image: ImageProperties) => (image.width, image.height)
      case None                         => (0, 0)
    }

    /**
     * Resolves the requested size for a pinned dimension, capped at the source's native size so a
     * thumbnail is never upscaled. For video the size is also rounded down to an even number, since
     * the h264 encoder requires even dimensions.
     */
    private def scaledDimensions(requested: Int, dimension: ThumbnailDimension, source: (Int, Int), even: Boolean): (Option[Int], Option[Int]) = {
      val (sourceWidth, sourceHeight) = source
      val native                      = if dimension == ThumbnailDimension.Width then sourceWidth else sourceHeight
      val capped                      = if native > 0 then math.min(requested, native) else requested
      val sized                       = if even then capped - (capped % 2) else capped
      (Option.when(dimension == ThumbnailDimension.Width)(sized), Option.when(dimension == ThumbnailDimension.Height)(sized))
    }

    /**
     * Builds a thumbnail operation only when the URL timestamp matches the resource's
     * canonical timestamp, preventing arbitrary timestamp injection.
     */
    def thumbnailOperation(urlTimestamp: Long, dimensionToken: String, resolutionKey: String, formatName: String, resolutions: ThumbnailResolutions, formats: ThumbnailFormats, resource: ResourceInfo): Option[ResourceOperation] = {
      val requestedSize       = resolutionKey.toIntOption.getOrElse(resolutions.default)
      val (dimension, pixels) = resolutions.resolve(dimensionToken, requestedSize)
      val format              = formats.resolve(formatName)
      val source              = sourceDimensions(resource)
      resource.basicContentProperties match {
        case Some(video: VideoProperties) =>
          val ts = resource.thumbnailTimestamp.getOrElse(video.durationInMillis / 3).toLong
          if urlTimestamp == ts then
            val (width, height) = scaledDimensions(pixels, dimension, source, even = true)
            Some(VideoThumbnail(width = width, height = height, timestamp = ts, format = format))
          else None
        case Some(_: ImageProperties)     =>
          val (width, height) = scaledDimensions(pixels, dimension, source, even = false)
          Some(ImageThumbnail(width = width, height = height, format = format))
        case _                            => None
      }
    }

    /**
     * Builds a clip operation only when the URL timestamp matches the resource's
     * canonical timestamp, preventing arbitrary start/end injection.
     */
    def clipOperation(urlTimestamp: Long, dimensionToken: String, resolutionKey: String, resolutions: ThumbnailResolutions, resource: ResourceInfo): Option[ResourceOperation] = {
      val requestedSize       = resolutionKey.toIntOption.getOrElse(resolutions.default)
      val (dimension, pixels) = resolutions.resolve(dimensionToken, requestedSize)
      val source              = sourceDimensions(resource)
      resource.basicContentProperties match {
        case Some(video: VideoProperties) =>
          val start = resource.thumbnailTimestamp.getOrElse(video.durationInMillis / 3).toLong
          if urlTimestamp == start then
            val end             = Math.min(video.durationInMillis.toLong, start + 3000L)
            val (width, height) = scaledDimensions(pixels, dimension, source, even = true)
            Some(VideoFragment(width = width, height = height, start = start, end = end))
          else None
        case _                            =>
          None
      }
    }
  }

  def apply(buckets: Map[BucketId, ResourceBucket], resolutions: ThumbnailResolutions, formats: ThumbnailFormats)(using apiSecurity: ApiSecurity): HttpRoutes[IO] = {

    // The content routes are not Tapir endpoints, so the access token has to be read from the cookie directly.
    def authToken(req: Request[IO]) =
      val accessToken = req.cookies.find(_.name == authCookieName).map(_.content)
      apiSecurity.decodeAccessToken(accessToken)

    def isAnonymouslyForbidden(req: Request[IO]): Boolean =
      apiSecurity.isLoginRequired && authToken(req).isAnonymous

    def isBucketHidden(req: Request[IO], bucketId: BucketId): Boolean =
      apiSecurity.userAccess(authToken(req)).hiddenBuckets.contains(bucketId)

    def getResource(req: Request[IO], bucketId: BucketId, resourceId: ResourceId): OptionT[IO, (ResourceBucket, Resource)] =
      if isBucketHidden(req, bucketId) then OptionT.none[IO, (ResourceBucket, Resource)]
      else
        for
          bucket   <- OptionT.fromOption[IO](buckets.get(bucketId))
          resource <- OptionT(bucket.getResource(resourceId))
        yield bucket -> resource

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
                                      case patterns.PublicThumbnailPattern(ts, dim, resKey, format) => patterns.thumbnailOperation(ts.toLong, dim, resKey, format, resolutions, formats, resource.info)
                                      case patterns.PublicClipPattern(ts, dim, resKey)              => patterns.clipOperation(ts.toLong, dim, resKey, resolutions, resource.info)
                                      case _                                                => None
                                    })
              derivedResource    <- OptionT(bucket.getOrCreate(ResourceId(resourceId), operation))
              response           <- OptionT.liftF(resourceContentsResponse(req, derivedResource)
                                      .map(r => r.addHeader(`Cache-Control`(`max-age`(365.days)))))
            yield response
          )
    }
  }
}

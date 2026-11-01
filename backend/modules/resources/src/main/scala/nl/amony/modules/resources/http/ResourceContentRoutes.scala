package nl.amony.modules.resources.http

import scala.concurrent.duration.FiniteDuration

import cats.data.OptionT
import cats.effect.IO
import org.http4s.*
import org.http4s.CacheDirective
import org.http4s.CacheDirective.{`max-age`, `must-revalidate`}
import org.http4s.dsl.io.*
import org.http4s.headers.{`Cache-Control`, `Retry-After`}
import scribe.Logging

import nl.amony.modules.auth.api.{ApiSecurity, authCookieName}
import nl.amony.modules.resources.api.*
import nl.amony.modules.resources.api.BucketRegistry
import nl.amony.modules.resources.http.ResourceDirectives.resourceContentsResponse
import nl.amony.platform.subprocess.{ProcessBusy, ProcessTimedOut}

object ResourceContentRoutes extends Logging {

  object patterns {

    // Public URL patterns: timestamp for cache-busting + pinned dimension + resolution size in pixels
    // + image format extension.
    // thumb_{version}_{timestamp}_{dim}_{size}.{format}  e.g. thumb_abc123_2863_w_768.avif       (videos and images)
    // clip_{version}_{start}_{end}_{dim}_{size}.mp4      e.g. clip_abc123_2863_5863_w_512.mp4    (videos only)
    val PublicThumbnailPattern             = raw"thumb_([a-z0-9]+)_(\d+)_([wh])_(\d+)\.([a-z0-9]+)".r
    val PublicClipPattern                  = raw"clip_([a-z0-9]+)_(\d+)_(\d+)_([wh])_(\d+)\.mp4".r
    val PublicThumbnailTileManifestPattern = raw"thumbtile_([a-z0-9]+)\.vtt".r
    val PublicThumbnailTileSpritePattern   = raw"thumbtile_([a-z0-9]+)_(\d+)\.webp".r

    /**
     * Builds a thumbnail operation only when the URL version matches the resource's
     * current content version and the timestamp matches its canonical timestamp,
     * preventing arbitrary timestamp injection and stale cache hits.
     */
    def thumbnailOperation(
      urlVersion: String,
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
      if urlVersion != ResourceOperations.contentVersion(resource) then None
      else
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

    /** Builds the manifest operation when the URL version matches the resource's current content version. */
    def thumbnailTileManifestOperation(urlVersion: String, resource: ResourceInfo, tiles: ThumbnailTiles): Option[VideoThumbnailTileVtt] =
      if urlVersion != ResourceOperations.contentVersion(resource) then None
      else
        resource.basicContentProperties match {
          case Some(video: VideoProperties) =>
            Some(VideoThumbnailTileVtt(intervalInMillis = tiles.intervalInMillis(video.durationInMillis.toLong)))
          case _                            => None
        }

    /**
     * Builds one sprite operation when the URL version matches and the sprite index exists for the
     * resource's duration, rejecting arbitrary index injection.
     */
    def thumbnailTileSpriteOperation(
      urlVersion: String,
      index: String,
      resource: ResourceInfo,
      tiles: ThumbnailTiles
    ): Option[VideoThumbnailTile] =
      if urlVersion != ResourceOperations.contentVersion(resource) then None
      else
        resource.basicContentProperties match {
          case Some(video: VideoProperties) =>
            val interval = tiles.intervalInMillis(video.durationInMillis.toLong)
            index.toIntOption.flatMap { parsed =>
              val operation = VideoThumbnailTile(intervalInMillis = interval, index = parsed)
              Option.when(operation.validate(resource).isRight)(operation)
            }
          case _                            => None
        }

    /**
     * Builds a clip operation only when the URL version matches the resource's
     * current content version and the URL start and end match the resource's canonical preview
     * range, preventing arbitrary range injection.
     */
    def clipOperation(
      urlVersion: String,
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
      if urlVersion != ResourceOperations.contentVersion(resource) then None
      else
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

  private def scopeDirective(scope: CacheScope): CacheDirective = scope match
    case CacheScope.Public  => CacheDirective.public
    case CacheScope.Private => CacheDirective.`private`()

  def apply(
    buckets: BucketRegistry,
    resolutions: ThumbnailResolutions,
    formats: ThumbnailFormats,
    tiles: ThumbnailTiles,
    cacheMaxAge: FiniteDuration,
    cacheScope: CacheScope
  )(using apiSecurity: ApiSecurity): HttpRoutes[IO] = {

    // The content routes are not Tapir endpoints, so the access token has to be read from the cookie directly.
    def authToken(req: Request[IO]) =
      val accessToken = req.cookies.find(_.name == authCookieName).map(_.content)
      apiSecurity.decodeAccessToken(accessToken)

    def getResource(req: Request[IO], bucketId: BucketId, resourceId: ResourceId): OptionT[IO, (ResourceBucket, Resource)] =
      OptionT(buckets.get(bucketId))
        .filter(bucket => authToken(req).canAccessBucket(bucket.requiredRole))
        .flatMap(bucket => OptionT(bucket.getResource(resourceId)).map(resource => bucket -> resource))

    def maybeResponse(option: OptionT[IO, Response[IO]]): IO[Response[IO]] =
      option.value.attempt.map {
        case Right(Some(response))          => response
        case Right(None)                    => Response(Status.NotFound)
        case Left(busy: ProcessBusy)        =>
          logger.debug(s"Rejecting content request: ${busy.getMessage}")
          Response(Status.ServiceUnavailable, headers = Headers(`Retry-After`.unsafeFromLong(5)))
        case Left(timeout: ProcessTimedOut) =>
          logger.warn(s"Content generation timed out: ${timeout.getMessage}")
          Response(Status.GatewayTimeout)
        case Left(error)                    => throw error
      }

    HttpRoutes.of[IO] {

      case req @ GET -> Root / "api" / "resources" / bucketId / resourceId / "content" =>
        maybeResponse:
          getResource(req, BucketId(bucketId), ResourceId(resourceId)).semiflatMap((_, resource) => resourceContentsResponse(req, resource.content))

      case req @ GET -> Root / "api" / "resources" / bucketId / resourceId / resourcePattern =>
        maybeResponse(
          for
            (bucket, resource) <- getResource(req, BucketId(bucketId), ResourceId(resourceId))
            operation          <- OptionT.fromOption(resourcePattern match {
                                    case patterns.PublicThumbnailPattern(version, ts, dim, resKey, format) =>
                                      patterns.thumbnailOperation(version, ts.toLong, dim, resKey, format, resolutions, formats, resource.info)
                                    case patterns.PublicClipPattern(version, start, end, dim, resKey)      =>
                                      patterns.clipOperation(version, start.toLong, end.toLong, dim, resKey, resolutions, resource.info)
                                    case patterns.PublicThumbnailTileManifestPattern(version)              =>
                                      patterns.thumbnailTileManifestOperation(version, resource.info, tiles)
                                    case patterns.PublicThumbnailTileSpritePattern(version, index)         =>
                                      patterns.thumbnailTileSpriteOperation(version, index, resource.info, tiles)
                                    case _                                                                 => None
                                  })
            derivedResource    <- OptionT(bucket.getOrCreate(ResourceId(resourceId), operation))
            response           <- OptionT.liftF(resourceContentsResponse(req, derivedResource)
                                    .map(r => r.addHeader(`Cache-Control`(`max-age`(cacheMaxAge), `must-revalidate`, scopeDirective(cacheScope)))))
          yield response
        )
    }
  }
}

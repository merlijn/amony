package nl.amony.modules.resources

import scala.concurrent.duration.FiniteDuration

import cats.effect.{IO, Resource}
import org.http4s.HttpRoutes
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import skunk.Session
import sttp.tapir.AnyEndpoint

import nl.amony.modules.auth.api.ApiSecurity
import nl.amony.modules.resources.api.{
  BucketRegistry,
  CacheScope,
  LocalDirectoryConfig,
  ResourceEvent,
  ResourceIndex,
  ThumbnailFormats,
  ThumbnailResolutions,
  ThumbnailTiles
}
import nl.amony.modules.resources.dal.{BucketsDal, ResourceDatabase}
import nl.amony.modules.resources.http.{BucketRoutes, CollectionRoutes, ResourceContentRoutes, ResourceRoutes}
import nl.amony.modules.resources.local.LocalDirectoryBucket
import nl.amony.platform.messagebus.MessageTopic
import nl.amony.platform.subprocess.{ProcessLimiter, ProcessSettings}
import nl.amony.platform.tapir.ServerEndpoints

/**
 * Wires the resources module: the resource database, the thumbnail configuration, the event bus the
 * buckets publish to and the bucket registry started from the stored configuration. The registry is a
 * [[Resource]], so the module is built through [[ResourceModule.resource]].
 */
class ResourceModule(
  private val resourceDatabase: ResourceDatabase,
  val bucketRegistry: BucketRegistry,
  val thumbResolutions: ThumbnailResolutions,
  val thumbFormats: ThumbnailFormats,
  val thumbTiles: ThumbnailTiles,
  val contentCacheMaxAge: FiniteDuration,
  val contentCacheScope: CacheScope
):

  /** The Tapir endpoints of the module. */
  def routes(resourceIndex: ResourceIndex)(using apiSecurity: ApiSecurity): ServerEndpoints[IO] =
    BucketRoutes.apply(bucketRegistry, resourceIndex) ++
      CollectionRoutes.apply(resourceDatabase, bucketRegistry) ++
      ResourceRoutes.apply(bucketRegistry)

  /** The non-Tapir content routes (downloads, thumbnails and clips), served as plain HttpRoutes. */
  def contentRoutes(using apiSecurity: ApiSecurity): HttpRoutes[IO] =
    ResourceContentRoutes.apply(bucketRegistry, thumbResolutions, thumbFormats, thumbTiles, contentCacheMaxAge, contentCacheScope)

object ResourceModule:

  def endpoints: List[AnyEndpoint] = ResourceRoutes.endpoints ++ BucketRoutes.endpoints ++ CollectionRoutes.endpoints

  def resource(
    config: ResourceConfig,
    processSettings: ProcessSettings,
    databasePool: Resource[IO, Session[IO]],
    eventTopic: MessageTopic[ResourceEvent]
  )(using Meter[IO], Tracer[IO]): Resource[IO, ResourceModule] =
    val thumbResolutions = ThumbnailResolutions(
      config.previews.allowedResolutions,
      config.previews.defaultResolution,
      config.previews.resolutionStepDown
    )
    val thumbFormats     = ThumbnailFormats(config.previews.supportedImageFormats, config.previews.formatOptions)
    val thumbTiles       = ThumbnailTiles(
      config.previews.tiles.intervalLadder,
      config.previews.tiles.targetFrames,
      config.previews.tiles.spriteWidth
    )
    val resourceDatabase = ResourceDatabase(databasePool)

    def bucketFactory(processLimiter: ProcessLimiter): BucketFactory = {
      case localConfig: LocalDirectoryConfig =>
        IO {
          val bucket = LocalDirectoryBucket(
            localConfig,
            config.parallelFactor,
            resourceDatabase,
            eventTopic,
            thumbFormats,
            thumbResolutions,
            processSettings,
            processLimiter
          )
          (bucket, bucket.sync())
        }
    }

    for
      processLimiter <- ProcessLimiter.resource(processSettings)
      bucketRegistry <- DatabaseBucketRegistry.resource(config.defaultBucket, BucketsDal(databasePool), eventTopic, bucketFactory(processLimiter))
    yield new ResourceModule(
      resourceDatabase,
      bucketRegistry,
      thumbResolutions,
      thumbFormats,
      thumbTiles,
      config.previews.cacheMaxAge,
      config.previews.cacheScope
    )

package nl.amony.modules.resources

import scala.concurrent.duration.FiniteDuration

import cats.effect.{IO, Resource}
import org.http4s.HttpRoutes
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import skunk.Session

import nl.amony.modules.auth.api.ApiSecurity
import nl.amony.modules.resources.api.{BucketRegistry, CacheScope, LocalDirectoryConfig, ResourceEvent, ThumbnailFormats, ThumbnailResolutions}
import nl.amony.modules.resources.dal.{BucketsDal, ResourceDatabase}
import nl.amony.modules.resources.http.{CollectionRoutes, ResourceContentRoutes, ResourceRoutes}
import nl.amony.modules.resources.local.LocalDirectoryBucket
import nl.amony.platform.messagebus.MessageTopic
import nl.amony.platform.tapir.dsl.ServerEndpoints

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
  val contentCacheMaxAge: FiniteDuration,
  val contentCacheScope: CacheScope
):

  /** The Tapir endpoints of the module. */
  def routes(using apiSecurity: ApiSecurity): ServerEndpoints[IO] =
    CollectionRoutes.apply(resourceDatabase, bucketRegistry) ++ ResourceRoutes.apply(bucketRegistry)

  /** The non-Tapir content routes (downloads, thumbnails and clips), served as plain HttpRoutes. */
  def contentRoutes(using apiSecurity: ApiSecurity): HttpRoutes[IO] =
    ResourceContentRoutes.apply(bucketRegistry, thumbResolutions, thumbFormats, contentCacheMaxAge, contentCacheScope)

object ResourceModule:

  def resource(
    config: ResourceConfig,
    databasePool: Resource[IO, Session[IO]],
    eventTopic: MessageTopic[ResourceEvent]
  )(using Meter[IO], Tracer[IO]): Resource[IO, ResourceModule] =
    val thumbResolutions = ThumbnailResolutions(
      config.previews.allowedResolutions,
      config.previews.defaultResolution,
      config.previews.resolutionStepDown
    )
    val thumbFormats     = ThumbnailFormats(config.previews.supportedImageFormats, config.previews.formatOptions)
    val resourceDatabase = ResourceDatabase(databasePool)

    val bucketFactory: BucketFactory = {
      case localConfig: LocalDirectoryConfig =>
        IO {
          val bucket = LocalDirectoryBucket(localConfig, config.parallelFactor, resourceDatabase, eventTopic, thumbFormats, thumbResolutions)
          (bucket, bucket.sync())
        }
    }

    for
      bucketRegistry <- DatabaseBucketRegistry.resource(config.defaultBucket, BucketsDal(databasePool), eventTopic, bucketFactory)
    yield new ResourceModule(
      resourceDatabase,
      bucketRegistry,
      thumbResolutions,
      thumbFormats,
      config.previews.cacheMaxAge,
      config.previews.cacheScope
    )

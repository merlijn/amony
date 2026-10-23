package nl.amony.modules.search.http

import cats.effect.IO
import scribe.Logging
import sttp.tapir.*

import nl.amony.modules.auth.api.*
import nl.amony.modules.resources.api.{BucketId, BucketRegistry}
import nl.amony.modules.resources.local.LocalDirectoryBucket
import nl.amony.modules.search.api.{Query, SearchIndexer, SearchService}
import nl.amony.platform.tapir.dsl.error.{ErrorResponse, SecurityError}
import nl.amony.platform.tapir.dsl.{RoutesModule, ServerEndpoints, routes}

/** Bucket operations that drive the search index and therefore live with the search module. */
object SearchBucketRoutes extends RoutesModule, Logging:

  private val actionErrorOutput: EndpointOutput[SecurityError] = ErrorResponse.securityErrors

  val reIndex =
    register(endpoint.tag("buckets").name("adminReindexBucket").description("Re-index all resources in a bucket.")
      .post.in("api" / "buckets" / path[BucketId]("bucketId") / "reindex")
      .securityIn(securityInput)
      .errorOut(actionErrorOutput))

  val fixNonStreamable =
    register(endpoint.name("adminFixNonStreamable").tag("buckets")
      .description("Remux all non-streamable resources in a bucket so they can be streamed progressively")
      .post.in("api" / "buckets" / path[BucketId]("bucketId") / "fix-non-streamable")
      .securityIn(securityInput)
      .errorOut(actionErrorOutput))

  def apply(searchService: SearchService, indexer: SearchIndexer, registry: BucketRegistry)(
    using apiSecurity: ApiSecurity
  ): ServerEndpoints[IO] =
    routes[IO] {
      serverLogic(endpoint = reIndex, requiredPermission = Permission.ManageBuckets) { _ => bucketId =>
        val result = registry.get(bucketId).flatMap:
          case None         => IO.unit
          case Some(bucket) =>
            logger.info(s"Re-indexing all resources in bucket '$bucketId'")

            for
              _ <- indexer.deleteBucket(bucketId)
              _ <- indexer.indexAll(bucket.getAllResources)
              _ <- indexer.forceCommit()
              _ <- IO(logger.info(s"Re-indexed all resources in bucket '$bucketId'"))
            yield ()

        result.map(Right(_))
      }

      serverLogic(endpoint = fixNonStreamable, requiredPermission = Permission.ManageBuckets) { _ => bucketId =>
        val result = registry.get(bucketId).flatMap:
          case Some(bucket: LocalDirectoryBucket) =>
            logger.info(s"Normalizing non-streamable resources in bucket '$bucketId'")
            searchService.searchAll(Query(n = 100, streamable = Some(false), includeBuckets = Set(bucketId)))
              .evalMap(resource => bucket.fixStreamability(resource.resourceId))
              .compile.drain >> IO(logger.info(s"Finished normalizing non-streamable resources in bucket '$bucketId'"))
          case _                                  =>
            IO(logger.info(s"Cannot normalize non-streamable resources in bucket '$bucketId'"))

        result.map(Right(_))
      }
    }

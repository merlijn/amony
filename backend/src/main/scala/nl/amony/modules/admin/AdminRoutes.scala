package nl.amony.modules.admin

import cats.effect.IO
import scribe.Logging
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.*

import nl.amony.lib.tapir.dsl.error.ErrorResponse
import nl.amony.lib.tapir.dsl.{RoutesModule, ServerEndpoints, routes, serverLogic}
import nl.amony.modules.auth.api.*
import nl.amony.modules.resources.api.{BucketId, ResourceBucket, ResourceId, ResourceInfo}
import nl.amony.modules.resources.http.{ResourceDto, toDto}
import nl.amony.modules.resources.local.LocalDirectoryBucket
import nl.amony.modules.search.api.{Query, SearchService}

object AdminRoutes extends RoutesModule, Logging:

  val errorOutput = ErrorResponse.securityErrors

  case object NdJson extends CodecFormat:
    override val mediaType: sttp.model.MediaType = sttp.model.MediaType.unsafeParse("application/x-ndjson")

  val reIndex =
    register(endpoint.tag("admin").name("adminReindexBucket").description("Re-index all resources in a bucket.")
      .post.in("api" / "admin" / "reindex")
      .in(query[BucketId]("bucketId").description("The id of the bucket to re-index."))
      .securityIn(securityInput)
      .errorOut(errorOutput))

  val refresh =
    register(endpoint.tag("admin").name("adminRefreshBucket").description("Refresh all resources in a bucket")
      .post.in("api" / "admin" / "refresh")
      .in(query[BucketId]("bucketId").description("The id of the bucket to refresh."))
      .securityIn(securityInput)
      .errorOut(errorOutput))

  val rescanMetaData =
    register(endpoint
      .tag("admin").name("adminRescanMetaData").description("Rescan the metadata of all files in a bucket")
      .post.in("api" / "admin" / "re-scan-metadata")
      .in(query[BucketId]("bucketId").description("The id of the bucket to re-scan."))
      .securityIn(securityInput)
      .errorOut(errorOutput))

  val reComputeHashes =
    register(endpoint.name("adminReComputeHashes").tag("admin").description("Recompute the hashes of all files in a bucket")
      .post.in("api" / "admin" / "re-compute-hashes")
      .in(query[BucketId]("bucketId").description("The id of the bucket to re-compute the hashes for."))
      .securityIn(securityInput)
      .errorOut(errorOutput))

  val generatePreviews =
    register(endpoint.name("adminGeneratePreviews").tag("admin")
      .description("Generate all configured thumbnails and preview clips for all resources in a bucket")
      .post.in("api" / "admin" / "generate-previews")
      .in(query[BucketId]("bucketId").description("The id of the bucket to generate previews for."))
      .securityIn(securityInput)
      .errorOut(errorOutput))

  val fixNonStreamable =
    register(endpoint.name("adminFixNonStreamable").tag("admin")
      .description("Remux all non-streamable resources in a bucket so they can be streamed progressively")
      .post.in("api" / "admin" / "fix-non-streamable")
      .in(query[BucketId]("bucketId").description("The id of the bucket to fix."))
      .securityIn(securityInput)
      .errorOut(errorOutput))

  val fixResourceStreamable =
    register(endpoint.name("adminFixResourceStreamable").tag("admin")
      .description("Remux a single non-streamable resource so it can be streamed progressively")
      .post.in("api" / "admin" / "fix-streamability" / path[BucketId]("bucketId") / path[ResourceId]("resourceId"))
      .securityIn(securityInput)
      .errorOut(errorOutput))

  val exportBucket =
    register(endpoint.name("adminExportBucket").tag("admin").description("Export all resources in a bucket")
      .get.in("api" / "admin" / "export" / path[BucketId]("bucketId"))
      .securityIn(securityInput)
      .out(streamBody(Fs2Streams[IO])(summon[Schema[ResourceDto]], NdJson))
      .errorOut(errorOutput))

  val importBucket =
    register(endpoint.name("adminImportBucket").tag("admin").description("Import all resources in a bucket")
      .post.in("api" / "admin" / "import" / path[BucketId]("bucketId"))
      .in(streamBody(Fs2Streams[IO])(summon[Schema[ResourceDto]], NdJson))
      .securityIn(securityInput)
      .errorOut(errorOutput))

  def apply(searchService: SearchService, buckets: Map[BucketId, ResourceBucket])(
    using apiSecurity: ApiSecurity
  ): ServerEndpoints[IO] = {

    routes[IO] {
      serverLogic(endpoint = reIndex, requiredPermission = Permission.Admin) { _ => bucketId =>
        val result = buckets.get(bucketId) match
          case None         => IO.unit
          case Some(bucket) =>
            logger.info(s"Re-indexing all resources in bucket '$bucketId'")

            for
              _ <- searchService.deleteBucket(bucketId)
              _ <- searchService.indexAll(bucket.getAllResources)
              _ <- searchService.forceCommit()
              _ <- IO(logger.info(s"Re-indexed all resources in bucket '$bucketId'"))
            yield ()

        result.map(Right(_))
      }

      serverLogic(endpoint = refresh, requiredPermission = Permission.Admin) { _ => bucketId =>
        val result = buckets.get(bucketId) match
          case Some(bucket: LocalDirectoryBucket) =>
            logger.info(s"Refreshing resources in bucket '$bucketId'")
            bucket.refresh() >> IO(logger.info(s"Finished refreshing resources in bucket '$bucketId'"))
          case _                                  =>
            IO(logger.info(s"Cannot refresh bucket '$bucketId'"))

        result.map(Right(_))
      }

      serverLogic(endpoint = rescanMetaData, requiredPermission = Permission.Admin) { _ => bucketId =>
        val result = buckets.get(bucketId) match
          case Some(bucket: LocalDirectoryBucket) =>
            logger.info(s"Re-scanning meta data of all resources in bucket '$bucketId'")
            bucket.reScanAllMetadata() >> IO(logger.info(s"Finished re-scanning meta data of all resources in bucket '$bucketId'"))
          case _                                  =>
            logger.info(s"Cannot re-scan meta data of bucket '$bucketId'")
            IO.unit

        result.map(Right(_))
      }

      serverLogic(endpoint = reComputeHashes, requiredPermission = Permission.Admin) { _ => bucketId =>
        val result = buckets.get(bucketId) match
          case Some(bucket: LocalDirectoryBucket) =>
            logger.info(s"Re-computing partialHashs of all resources in bucket '$bucketId'")
            bucket.reComputePartialHashs() >> IO(logger.info(s"Finished re-computing partialHashs of all resources in bucket '$bucketId'"))
          case _                                  =>
            logger.info(s"Cannot re-compute partialHashs of bucket '$bucketId'")
            IO.unit

        result.map(Right(_))
      }

      serverLogic(endpoint = generatePreviews, requiredPermission = Permission.Admin) { _ => bucketId =>
        val result = buckets.get(bucketId) match
          case Some(bucket: LocalDirectoryBucket) =>
            logger.info(s"Generating previews for all resources in bucket '$bucketId'")
            bucket.generateAllPreviews() >> IO(logger.info(s"Finished generating previews for bucket '$bucketId'"))
          case _                                  =>
            logger.info(s"Cannot generate previews for bucket '$bucketId'")
            IO.unit

        result.map(Right(_))
      }

      serverLogic(endpoint = fixNonStreamable, requiredPermission = Permission.Admin) { _ => bucketId =>
        val result = buckets.get(bucketId) match
          case Some(bucket: LocalDirectoryBucket) =>
            logger.info(s"Normalizing non-streamable resources in bucket '$bucketId'")
            searchService.searchAll(Query(n = 100, streamable = Some(false), includeBuckets = Set(bucketId)))
              .evalMap(resource => bucket.fixStreamability(resource.resourceId))
              .compile.drain >> IO(logger.info(s"Finished normalizing non-streamable resources in bucket '$bucketId'"))
          case _                                  =>
            IO(logger.info(s"Cannot normalize non-streamable resources in bucket '$bucketId'"))

        result.map(Right(_))
      }

      serverLogic(endpoint = fixResourceStreamable, requiredPermission = Permission.Admin) { _ => (bucketId, resourceId) =>
        buckets.get(bucketId) match
          case Some(bucket: LocalDirectoryBucket) =>
            logger.info(s"Normalizing resource '$resourceId' in bucket '$bucketId'")
            bucket.fixStreamability(resourceId).as(Right(()))
          case _                                  =>
            logger.info(s"Cannot normalize resource '$resourceId' in bucket '$bucketId'")
            IO.pure(Right(()))
      }

      serverLogic(endpoint = exportBucket, requiredPermission = Permission.Admin) { _ => bucketId =>
        buckets.get(bucketId) match
          case Some(bucket: LocalDirectoryBucket) =>
            logger.info(s"Exporting resources in bucket '$bucketId'")
            val stream = bucket.getAllResources.map(resource => summon[io.circe.Codec[ResourceDto]].apply(toDto(resource)).noSpaces).intersperse("\n")
              .through(fs2.text.utf8.encode[IO])
            IO(Right(stream))
          case _                                  =>
            logger.info(s"Cannot backup bucket '$bucketId'")
            IO(Right(fs2.Stream.empty[IO]))
      }

      serverLogic(endpoint = importBucket, requiredPermission = Permission.Admin)(_ =>
        (bucketId, stream) =>
          buckets.get(bucketId) match
            case Some(bucket: LocalDirectoryBucket) =>
              logger.info(s"Importing resources into bucket '$bucketId'")

              val resources: fs2.Stream[IO, ResourceInfo] = stream.through(fs2.text.utf8.decode[IO]).through(fs2.text.lines)
                .map(line => io.circe.parser.decode[ResourceDto](line).map(_.toDomain())).flatMap {
                  case Right(resource) => fs2.Stream.emit(resource)
                  case Left(error)     => fs2.Stream.raiseError[IO](error)
                }

              bucket.importBackup(resources).map(_ => Right(()))
            case _                                  =>
              logger.info(s"Cannot import into bucket '$bucketId'")
              IO(Right(()))
      )
    }
  }

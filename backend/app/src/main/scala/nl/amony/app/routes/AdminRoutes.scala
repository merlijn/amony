package nl.amony.app.routes

import cats.effect.IO
import nl.amony.lib.auth.{ApiSecurity, AuthToken, JwtDecoder, Roles, SecurityError, SecurityInput, securityErrors, securityInput}
import nl.amony.service.resources.domain.ResourceInfo
import nl.amony.service.resources.{Resource, ResourceBucket}
import nl.amony.service.resources.local.LocalDirectoryBucket
import nl.amony.service.resources.web.dto.{ResourceDto, toDto}
import nl.amony.service.resources.web.oneOfList
import nl.amony.service.search.domain.SearchService
import org.http4s.*
import scribe.Logging
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.*
import sttp.tapir.server.http4s.{Http4sServerInterpreter, Http4sServerOptions}

object AdminRoutes extends Logging:

  val errorOutput: EndpointOutput[SecurityError] = oneOfList(securityErrors)

  val reIndex =
    endpoint
      .name("adminReindexBucket")
      .tag("admin")
      .description("Re-index all resources in a bucket.")
      .post.in("api" / "admin" / "reindex")
      .in(query[String]("bucketId").description("The id of the bucket to re-index."))
      .securityIn(securityInput)
      .errorOut(errorOutput)

  val refresh =
    endpoint
      .name("adminRefreshBucket")
      .tag("admin")
      .description("Refresh all resources in a bucket")
      .post.in("api" / "admin" / "refresh")
      .in(query[String]("bucketId").description("The id of the bucket to re-index."))
      .securityIn(securityInput)
      .errorOut(errorOutput)

  val rescanMetaData =
    endpoint
      .name("adminRescanMetaData")
      .tag("admin")
      .description("Rescan the metadata of all files in a bucket")
      .post.in("api" / "admin" / "re-scan-metadata")
      .in(query[String]("bucketId").description("The id of the bucket to re-scan."))
      .securityIn(securityInput)
      .errorOut(errorOutput)

  val reComputeHashes =
    endpoint
      .name("adminReComputeHashes")
      .tag("admin")
      .description("Recompute the hashes of all files in a bucket")
      .post.in("api" / "admin" / "re-compute-hashes")
      .in(query[String]("bucketId").description("The id of the bucket to re-scan."))
      .securityIn(securityInput)
      .errorOut(errorOutput)

  private case class NdJson() extends CodecFormat {
    override val mediaType: sttp.model.MediaType = sttp.model.MediaType.unsafeParse("application/x-ndjson")
  }

  val exportBucket =
    endpoint
      .name("adminExportBucket")
      .tag("admin")
      .description("Export all resources in a bucket")
      .get.in("api" / "admin" / "export" / path[String]("bucketId"))
      .securityIn(securityInput)
      .out(streamBody(Fs2Streams[IO])(summon[Schema[ResourceDto]], NdJson()))
      .errorOut(errorOutput)

  val importBucket =
    endpoint
      .name("adminImportBucket")
      .tag("admin")
      .description("Import all resources in a bucket")
      .post.in("api" / "admin" / "import" / path[String]("bucketId"))
      .in(streamBody(Fs2Streams[IO])(summon[Schema[ResourceDto]], NdJson()))
      .securityIn(securityInput)
      .errorOut(errorOutput)

  val endpoints = List(reIndex, refresh, rescanMetaData, reComputeHashes, exportBucket)

  def apply(searchService: SearchService, buckets: Map[String, ResourceBucket], apiSecurity: ApiSecurity)(using serverOptions: Http4sServerOptions[IO]): HttpRoutes[IO] = {

    val reIndexImpl =
      reIndex
        .serverSecurityLogicPure(apiSecurity.requireRole(Roles.Admin))
        .serverLogicSuccess(_ => bucketId =>
          buckets.get(bucketId) match
            case None         => IO.unit
            case Some(bucket) =>
              logger.info(s"Re-indexing all resources in bucket '$bucketId'")

              def deleteBucket() = searchService.deleteBucket(bucketId)
              def commit: IO[Unit] = searchService.forceCommit()

              val resourceStream = bucket.getAllResources()
              
              deleteBucket() >> 
                searchService.indexAll(resourceStream).flatMap { _ =>
                  commit >> IO(logger.info(s"Re-indexed all resources in bucket '$bucketId'"))
                }
        )

    val refreshImpl =
      refresh
        .serverSecurityLogicPure(apiSecurity.requireRole(Roles.Admin))
        .serverLogicSuccess(_ => bucketId =>
          buckets.get(bucketId) match
            case Some(bucket: LocalDirectoryBucket) =>
              logger.info(s"Refreshing resources in bucket '$bucketId'")
              bucket.refresh() >> IO(logger.info(s"Finished refreshing resources in bucket '$bucketId'"))
            case _ => 
              IO(logger.info(s"Cannot refresh bucket '$bucketId'"))
        )

    val rescanMetaDataImpl =
      rescanMetaData
        .serverSecurityLogicPure(apiSecurity.requireRole(Roles.Admin))
        .serverLogicSuccess(_ => bucketId =>
          buckets.get(bucketId) match
            case Some(bucket: LocalDirectoryBucket) =>
              logger.info(s"Re-scanning meta data of all resources in bucket '$bucketId'")
              bucket.reScanAllMetadata() >> IO(logger.info(s"Finished re-scanning meta data of all resources in bucket '$bucketId'"))
            case _ =>
              logger.info(s"Cannot re-scan meta data of bucket '$bucketId'")
              IO.unit
        )

    val recomputeHashesImpl =
      reComputeHashes
        .serverSecurityLogicPure(apiSecurity.requireRole(Roles.Admin))
        .serverLogicSuccess(_ => bucketId =>
          buckets.get(bucketId) match
            case Some(bucket: LocalDirectoryBucket) =>
              logger.info(s"Re-computing hashes of all resources in bucket '$bucketId'")
              bucket.reComputeHashes() >> IO(logger.info(s"Finished re-computing hashes of all resources in bucket '$bucketId'"))
            case _ =>
              logger.info(s"Cannot re-compute hashes of bucket '$bucketId'")
              IO.unit
        )

    val exportBucketImpl =
      exportBucket
        .serverSecurityLogicPure(apiSecurity.requireRole(Roles.Admin))
        .serverLogic(_ => bucketId =>
          buckets.get(bucketId) match
            case Some(bucket: LocalDirectoryBucket) =>
              logger.info(s"Exporting resources in bucket '$bucketId'")
              val stream = bucket.getAllResources().map(resource => ResourceDto.derived$Codec.apply(toDto(resource)).noSpaces).intersperse("\n").through(fs2.text.utf8.encode[IO])
              IO(Right(stream))
            case _ =>
              logger.info(s"Cannot backup bucket '$bucketId'")
              IO(Right(fs2.Stream.empty[IO]))
        )

    val importBucketImpl =
      importBucket
        .serverSecurityLogicPure(apiSecurity.publicEndpoint)
        .serverLogic(_ => (bucketId, stream) =>
          buckets.get(bucketId) match
            case Some(bucket: LocalDirectoryBucket) =>
              logger.info(s"Importing resources into bucket '$bucketId'")

              val resources: fs2.Stream[IO, ResourceInfo] = stream
                .through(fs2.text.utf8.decode[IO])
                .through(fs2.text.lines)
                .map { line => io.circe.parser.decode[ResourceDto](line).map(_.toDomain()) }
                .flatMap {
                  case Right(resource) => fs2.Stream.emit(resource)
                  case Left(error)     => fs2.Stream.raiseError[IO](error)
                }

                bucket.importBackup(resources).map(_ => Right(()))
            case _ =>
              logger.info(s"Cannot import into bucket '$bucketId'")
              IO(Right(()))
        )

    Http4sServerInterpreter[IO](serverOptions).toRoutes(
      List(reIndexImpl, refreshImpl, rescanMetaDataImpl, recomputeHashesImpl, exportBucketImpl, importBucketImpl)
    )
  }

package nl.amony.modules.admin

import cats.effect.IO
import org.http4s.*
import scribe.Logging
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.*
import sttp.tapir.server.http4s.{Http4sServerInterpreter, Http4sServerOptions}

import nl.amony.modules.auth.api.*
import nl.amony.modules.resources.api.{ResourceBucket, ResourceInfo}
import nl.amony.modules.resources.http.{ResourceDto, oneOfList, toDto}
import nl.amony.modules.resources.local.LocalDirectoryBucket
import nl.amony.modules.search.api.SearchService

object AdminRoutes extends Logging:

  val errorOutput: EndpointOutput[SecurityError] = oneOfList(securityErrors)

  case object NdJson extends CodecFormat:
    override val mediaType: sttp.model.MediaType = sttp.model.MediaType.unsafeParse("application/x-ndjson")

  val reIndex =
    endpoint.tag("admin").name("adminReindexBucket").description("Re-index all resources in a bucket.")
      .post.in("api" / "admin" / "reindex")
      .in(query[String]("bucketId").description("The id of the bucket to re-index."))
      .securityIn(securityInput)
      .errorOut(errorOutput)

  val refresh =
    endpoint.tag("admin").name("adminRefreshBucket").description("Refresh all resources in a bucket")
      .post.in("api" / "admin" / "refresh")
      .in(query[String]("bucketId").description("The id of the bucket to re-index."))
      .securityIn(securityInput)
      .errorOut(errorOutput)

  val rescanMetaData =
    endpoint
      .tag("admin").name("adminRescanMetaData").description("Rescan the metadata of all files in a bucket")
      .post.in("api" / "admin" / "re-scan-metadata")
      .in(query[String]("bucketId").description("The id of the bucket to re-scan."))
      .securityIn(securityInput)
      .errorOut(errorOutput)

  val reComputeHashes =
    endpoint.name("adminReComputeHashes").tag("admin").description("Recompute the hashes of all files in a bucket")
      .post.in("api" / "admin" / "re-compute-hashes")
      .in(query[String]("bucketId").description("The id of the bucket to re-scan."))
      .securityIn(securityInput)
      .errorOut(errorOutput)

  val exportBucket =
    endpoint.name("adminExportBucket").tag("admin").description("Export all resources in a bucket")
      .get.in("api" / "admin" / "export" / path[String]("bucketId"))
      .securityIn(securityInput)
      .out(streamBody(Fs2Streams[IO])(summon[Schema[ResourceDto]], NdJson))
      .errorOut(errorOutput)

  val importBucket =
    endpoint.name("adminImportBucket").tag("admin").description("Import all resources in a bucket")
      .post.in("api" / "admin" / "import" / path[String]("bucketId"))
      .in(streamBody(Fs2Streams[IO])(summon[Schema[ResourceDto]], NdJson))
      .securityIn(securityInput)
      .errorOut(errorOutput)

  val endpoints = List(reIndex, refresh, rescanMetaData, reComputeHashes, exportBucket)

  def apply(searchService: SearchService, buckets: Map[String, ResourceBucket], apiSecurity: ApiSecurity)(
    using serverOptions: Http4sServerOptions[IO]
  ): HttpRoutes[IO] = {

    val reIndexImpl = reIndex.serverSecurityLogicPure(apiSecurity.requireRole(Role.Admin)).serverLogicSuccess(
      _ =>
        bucketId =>
          buckets.get(bucketId) match
            case None         => IO.unit
            case Some(bucket) =>
              logger.info(s"Re-indexing all resources in bucket '$bucketId'")

              for {
                _ <- searchService.deleteBucket(bucketId)
                _ <- searchService.indexAll(bucket.getAllResources)
                _ <- searchService.forceCommit()
                _ <- IO(logger.info(s"Re-indexed all resources in bucket '$bucketId'"))
              } yield ()
    )

    val refreshImpl =
      refresh.serverSecurityLogicPure(apiSecurity.requireRole(Role.Admin))
        .serverLogicSuccess(
          _ =>
            bucketId =>
              buckets.get(bucketId) match
                case Some(bucket: LocalDirectoryBucket) =>
                  logger.info(s"Refreshing resources in bucket '$bucketId'")
                  bucket.refresh() >> IO(logger.info(s"Finished refreshing resources in bucket '$bucketId'"))
                case _                                  =>
                  IO(logger.info(s"Cannot refresh bucket '$bucketId'"))
        )

    val rescanMetaDataImpl = rescanMetaData.serverSecurityLogicPure(apiSecurity.requireRole(Role.Admin)).serverLogicSuccess(
      _ =>
        bucketId =>
          buckets.get(bucketId) match
            case Some(bucket: LocalDirectoryBucket) =>
              logger.info(s"Re-scanning meta data of all resources in bucket '$bucketId'")
              bucket.reScanAllMetadata() >> IO(logger.info(s"Finished re-scanning meta data of all resources in bucket '$bucketId'"))
            case _                                  =>
              logger.info(s"Cannot re-scan meta data of bucket '$bucketId'")
              IO.unit
    )

    val recomputeHashesImpl = reComputeHashes.serverSecurityLogicPure(apiSecurity.requireRole(Role.Admin)).serverLogicSuccess(
      _ =>
        bucketId =>
          buckets.get(bucketId) match
            case Some(bucket: LocalDirectoryBucket) =>
              logger.info(s"Re-computing hashes of all resources in bucket '$bucketId'")
              bucket.reComputeHashes() >> IO(logger.info(s"Finished re-computing hashes of all resources in bucket '$bucketId'"))
            case _                                  =>
              logger.info(s"Cannot re-compute hashes of bucket '$bucketId'")
              IO.unit
    )

    val exportBucketImpl = exportBucket.serverSecurityLogicPure(apiSecurity.requireRole(Role.Admin)).serverLogic(
      _ =>
        bucketId =>
          buckets.get(bucketId) match
            case Some(bucket: LocalDirectoryBucket) =>
              logger.info(s"Exporting resources in bucket '$bucketId'")
              val stream = bucket.getAllResources.map(resource => ResourceDto.derived$Codec.apply(toDto(resource)).noSpaces).intersperse("\n")
                .through(fs2.text.utf8.encode[IO])
              IO(Right(stream))
            case _                                  =>
              logger.info(s"Cannot backup bucket '$bucketId'")
              IO(Right(fs2.Stream.empty[IO]))
    )

    val importBucketImpl = importBucket.serverSecurityLogicPure(apiSecurity.publicEndpoint).serverLogic(
      _ =>
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

    Http4sServerInterpreter[IO](serverOptions)
      .toRoutes(List(reIndexImpl, refreshImpl, rescanMetaDataImpl, recomputeHashesImpl, exportBucketImpl, importBucketImpl))
  }

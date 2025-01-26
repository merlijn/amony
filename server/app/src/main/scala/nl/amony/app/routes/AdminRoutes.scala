package nl.amony.app.routes

import cats.effect.IO
import nl.amony.service.auth.tapir.{securityErrors, securityInput}
import nl.amony.service.auth.{Authenticator, JwtDecoder, Roles, SecurityError}
import nl.amony.service.resources.api.ResourceInfo
import nl.amony.service.resources.{Resource, ResourceBucket}
import nl.amony.service.resources.local.LocalDirectoryBucket
import nl.amony.service.resources.web.oneOfList
import nl.amony.service.search.api.ForceCommitRequest
import nl.amony.service.search.api.SearchServiceGrpc.SearchService
import org.http4s.*
import scribe.Logging
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

  val backupBucket =
    endpoint
      .name("adminBackupBucket")
      .tag("admin")
      .description("Backup all resources in a bucket")
      .get.in("api" / "admin" / "backup" / path[String]("bucketId"))
      .securityIn(securityInput)
      .errorOut(errorOutput)
  
  val endpoints = List(reIndex, refresh, rescanMetaData)

  def apply(searchService: SearchService, buckets: Map[String, ResourceBucket], jwtDecoder: JwtDecoder)(using serverOptions: Http4sServerOptions[IO]): HttpRoutes[IO] = {

    val authenticator = Authenticator(jwtDecoder)

    val reIndexImpl =
      reIndex
        .serverSecurityLogic(authenticator.requireRole(Roles.Admin))
        .serverLogicSuccess(_ => bucketId =>
          buckets.get(bucketId) match
            case None         => IO.unit
            case Some(bucket) =>
              logger.info(s"Re-indexing all resources in bucket '$bucketId'")
              
              def indexResource(resource: ResourceInfo) = IO.fromFuture(IO(searchService.index(resource))) >> IO.unit
              def commit: IO[Unit] = IO.fromFuture(IO(searchService.forceCommit(ForceCommitRequest()))) >> IO.unit
              
              bucket
                .getAllResources().foreach(indexResource)
                .compile
                .drain >> commit >> IO(logger.info(s"Finished re-indexing all resources in bucket '$bucketId'"))
        )

    val refreshImpl =
      refresh
        .serverSecurityLogic(authenticator.requireRole(Roles.Admin))
        .serverLogicSuccess(_ => bucketId =>
          buckets.get(bucketId) match
            case Some(bucket: LocalDirectoryBucket[_]) =>
              logger.info(s"Refreshing resources in bucket '$bucketId'")
              bucket.refresh() >> IO(logger.info(s"Finished refreshing resources in bucket '$bucketId'"))
            case _ => 
              IO(logger.info(s"Cannot refresh bucket '$bucketId'"))
        )

    val rescanMetaDataImpl =
      rescanMetaData
        .serverSecurityLogic(authenticator.requireRole(Roles.Admin))
        .serverLogicSuccess(_ => bucketId =>
          buckets.get(bucketId) match
            case Some(bucket: LocalDirectoryBucket[_]) =>
              logger.info(s"Re-scanning meta data of all resources in bucket '$bucketId'")
              bucket.reScanAllMetadata() >> IO(logger.info(s"Finished re-scanning meta data of all resources in bucket '$bucketId'"))
            case _ =>
              logger.info(s"Cannot re-scan meta data of bucket '$bucketId'")
              IO.unit
        )

    Http4sServerInterpreter[IO](serverOptions).toRoutes(
      List(reIndexImpl, refreshImpl, rescanMetaDataImpl)
    )
  }

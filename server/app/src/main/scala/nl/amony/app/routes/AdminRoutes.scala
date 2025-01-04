package nl.amony.app.routes

import cats.effect.IO
import nl.amony.service.auth.tapir.{securityErrors, securityInput}
import nl.amony.service.auth.{Authenticator, JwtDecoder, Roles, SecurityError}
import nl.amony.service.resources.ResourceBucket
import nl.amony.service.resources.local.LocalDirectoryBucket
import nl.amony.service.resources.web.oneOfList
import nl.amony.service.search.api.SearchServiceGrpc.SearchService
import org.http4s.*
import scribe.Logging
import sttp.tapir.*
import sttp.tapir.server.http4s.Http4sServerInterpreter

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

  val endpoints = List(reIndex, refresh, rescanMetaData)

  def apply(searchService: SearchService, buckets: Map[String, ResourceBucket], jwtDecoder: JwtDecoder): HttpRoutes[IO] = {

    val authenticator = Authenticator(jwtDecoder)

    val reIndexImpl =
      reIndex
        .serverSecurityLogic(authenticator.requireRole(Roles.Admin))
        .serverLogicSuccess(_ => bucketId =>
          buckets.get(bucketId) match
            case None         => IO.unit
            case Some(bucket) =>
              logger.info(s"Re-indexing all resources.")
              bucket
                .getAllResources().foreach { resource => IO.fromFuture(IO(searchService.index(resource))).map(_ => ()) }
                .compile
                .drain
        )

    val refreshImpl =
      refresh
        .serverSecurityLogic(authenticator.requireRole(Roles.Admin))
        .serverLogicSuccess(_ => bucketId =>
          buckets.get(bucketId) match
            case None => IO.unit
            case Some(bucket: LocalDirectoryBucket[_]) =>
              logger.info(s"Re-indexing all resources.")
              bucket.refresh()
            case _ => IO.unit
        )

    val rescanMetaDataImpl =
      rescanMetaData
        .serverSecurityLogic(authenticator.requireRole(Roles.Admin))
        .serverLogicSuccess(_ => bucketId =>
          buckets.get(bucketId) match
            case None => IO.unit
            case Some(bucket: LocalDirectoryBucket[_]) =>
              logger.info(s"Re-indexing all resources.")
              bucket.reScanAllMetadata().flatMap(_ => IO.unit)
            case _ => IO.unit
        )

    Http4sServerInterpreter[IO]().toRoutes(
      List(reIndexImpl, refreshImpl)
    )
  }

package nl.amony.webserver.routes

import cats.effect.IO
import nl.amony.service.resources.ResourceBucket
import nl.amony.service.search.api.SearchServiceGrpc.SearchService
import org.http4s.*
import org.http4s.dsl.io.*
import scribe.Logging
import cats.effect.unsafe.IORuntime
import nl.amony.lib.ffmpeg.FFMpeg
import nl.amony.service.resources.local.LocalDirectoryBucket

import scala.concurrent.duration.*

object AdminRoutes extends Logging:

  def apply(searchService: SearchService, buckets: Map[String, ResourceBucket])(using runtime: IORuntime): HttpRoutes[IO] = {
    HttpRoutes.of[IO]:
      case req @ POST -> Root / "api" / "admin" / "re-index" =>
        req.params.get("bucketId").flatMap(buckets.get) match {
          case None => BadRequest("bucketId parameter missing or bucket not found.")
          case Some(bucket) =>
            logger.info(s"Re-indexing all resources.")
            bucket
              .getAllResources().foreach { resource => IO.fromFuture(IO(searchService.index(resource))).map(_ => ()) }
              .compile
              .drain
              .flatMap(_ => Ok())
        }

      case req @ POST -> Root / "api" / "admin" / "refresh" =>
        req.params.get("bucketId").flatMap(buckets.get) match {
          case None => BadRequest("bucketId parameter missing or bucket not found.")
          case Some(bucket: LocalDirectoryBucket[_]) =>
            logger.info(s"Refreshing bucket: ${bucket.id}")
            bucket.refresh().flatMap(_ => Ok())
          case _ => BadRequest("Bucket is not a LocalDirectoryBucket.")
        }

      case req @ POST -> Root / "api" / "admin" / "backup" =>
        req.params.get("bucketId").flatMap(buckets.get) match {
          case None => BadRequest("bucketId parameter missing or bucket not found.")
          case Some(bucket: LocalDirectoryBucket[_]) =>
            logger.info(s"Refreshing bucket: ${bucket.id}")
            bucket.refresh().flatMap(_ => Ok())
          case _ => BadRequest("Bucket is not a LocalDirectoryBucket.")
        }

      case req @ POST -> Root / "api" / "admin" / "re-scan-metadata" =>
        req.params.get("bucketId").flatMap(buckets.get) match {
          case None => BadRequest("bucketId parameter missing or bucket not found.")
          case Some(bucket: LocalDirectoryBucket[_]) =>
            logger.info(s"Re-indexing all resources.")
            bucket.reScanAllMetadata().flatMap(_ => Ok())
          case _ => BadRequest("Bucket is not a LocalDirectoryBucket.")
        }

      case req @ POST -> Root / "api" / "admin" / "logging" / "set-log-level" =>
        req.params.get("level") match {
          case None => BadRequest("level parameter missing.")
          case Some(level) =>
            logger.info(s"Setting log level to $level.")
            scribe.Logger.root
              .clearHandlers()
              .clearModifiers()
              .withHandler(minimumLevel = Some(scribe.Level(level)))
              .replace()
            Ok()
        }

      case req @ GET -> Root / "api" / "admin" / "logging" / "tail" =>
        Ok(fs2.Stream.awakeEvery[IO](1.seconds).map { d => d.toString.appended('\n') }.onFinalize(IO(logger.info("Tail logging stopped."))))
  }

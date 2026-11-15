package nl.amony.modules.resources.local

import scala.concurrent.duration.*

import cats.data.EitherT
import cats.effect.IO
import cats.syntax.all.*
import fs2.Stream
import skunk.Session

import nl.amony.modules.auth.api.UserId
import nl.amony.modules.resources.api.*
import nl.amony.platform.messagebus.{CommandBus, CommandState}

private[local] enum Prepared:
  case Streamability(tempFile: Option[String])
  case Upload(value: PreparedUpload)

/** Submits resource commands and processes them, so the buckets and the consumer share the same wiring. */
final class ResourceCommands(bus: CommandBus[ResourceCommand], timeout: FiniteDuration = 30.minutes):

  def submitFixStreamability(bucket: ResourceBucket, resourceId: ResourceId): IO[Either[ResourceCommandError, Unit]] =
    bucket.checkStreamabilityFix(resourceId).flatMap:
      case Left(error) => IO.pure(Left(error))
      case Right(())   =>
        val command = FixStreamability(bucket.id, resourceId)
        for
          commandId <- bus.submit(command.key, command)
          status    <- bus.awaitCompletion(commandId, timeout)
        yield status.state match
          case CommandState.Succeeded => Right(())
          case _                      => Left(ResourceCommandError.Rejected(status.lastError.getOrElse("Failed to fix streamability")))

  /** Streams the request body to the bucket's temp directory, then queues the processing as a command and awaits completion. */
  def submitUpload(
    bucket: ResourceBucket,
    userId: UserId,
    fileName: String,
    source: fs2.Stream[IO, Byte]
  ): IO[Either[UploadError, ResourceInfo]] =
    bucket.stageUpload(fileName, source).flatMap:
      case Left(error)   => IO.pure(Left(error))
      case Right(staged) =>
        val command = UploadResource(bucket.id, staged.resourceId, userId, staged.fileName, staged.tempFile)
        val submit  = bus
          .submit(command.key, command)
          .handleErrorWith(error => bucket.discardUpload(staged.tempFile) >> IO.raiseError(error))
        for
          commandId <- submit
          status    <- bus.awaitCompletion(commandId, timeout)
          result    <- status.state match
                         case CommandState.Succeeded =>
                           bucket.getResource(staged.resourceId).map {
                             case Some(resource) => Right(resource.info)
                             case None           => Left(UploadError.StorageError("Uploaded resource was not found after completion"))
                           }
                         case _                      =>
                           IO.pure(Left(UploadError.StorageError(status.lastError.getOrElse("Failed to store the uploaded resource"))))
        yield result

  def processAtLeastOnce(registry: BucketRegistry): Stream[IO, Unit] =
    bus.processAtLeastOnce(work(registry))(commit(registry))

  /** Slow and non-transactional: remuxes streamability or prepares an upload, all inside the bucket's temp directory. */
  private def work(registry: BucketRegistry)(command: ResourceCommand): IO[Either[String, Prepared]] =
    command match
      case FixStreamability(bucketId, resourceId) =>
        (for
          bucket   <- EitherT(getBucket(registry, bucketId))
          tempFile <- EitherT(bucket.remuxForStreaming(resourceId)).leftMap(_.message)
        yield Prepared.Streamability(tempFile)).value
      case upload: UploadResource                 =>
        (for
          bucket <- EitherT(getBucket(registry, upload.bucketId))
          value  <- EitherT(bucket.prepareUpload(upload)).leftMap(_.message)
        yield Prepared.Upload(value)).value

  /** Fast and transactional: persists the result together with the command's success. */
  private def commit(registry: BucketRegistry)(session: Session[IO], command: ResourceCommand, prepared: Prepared): IO[Either[String, Unit]] =
    (command, prepared) match
      case (FixStreamability(bucketId, resourceId), Prepared.Streamability(tempFile)) =>
        (for
          bucket <- EitherT(getBucket(registry, bucketId))
          _      <- EitherT(bucket.commitStreamabilityFix(session, resourceId, tempFile)).leftMap(_.message)
        yield ()).value
      case (upload: UploadResource, Prepared.Upload(value))                           =>
        (for
          bucket <- EitherT(getBucket(registry, upload.bucketId))
          _      <- EitherT(bucket.commitUpload(session, upload, value)).leftMap(_.message)
        yield ()).value
      case _                                                                          =>
        IO.pure(Left("Unexpected resource command preparation"))

  private def getBucket(registry: BucketRegistry, bucketId: BucketId): IO[Either[String, ResourceBucket]] =
    registry.get(bucketId).map(_.toRight(s"No bucket '$bucketId'"))

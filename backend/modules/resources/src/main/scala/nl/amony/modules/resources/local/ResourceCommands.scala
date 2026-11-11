package nl.amony.modules.resources.local

import cats.data.EitherT
import cats.effect.IO
import cats.syntax.all.*
import fs2.Stream
import skunk.Session

import nl.amony.modules.auth.api.UserId
import nl.amony.modules.resources.api.*
import nl.amony.platform.messagebus.{CommandBus, CommandId, CommandStatus}

enum ResourceCommandError:
  case NotFound
  case Rejected(message: String)

private[local] enum Prepared:
  case Streamability(tempFile: Option[String])
  case Upload(value: PreparedUpload)

/** Submits resource commands and processes them, so the routes and the consumer share the same wiring. */
final class ResourceCommands(registry: BucketRegistry, bus: CommandBus[ResourceCommand]):

  def submitFixStreamability(bucketId: BucketId, resourceId: ResourceId): IO[Either[ResourceCommandError, CommandId]] =
    registry.get(bucketId).flatMap:
      case Some(bucket: LocalDirectoryBucket) =>
        bucket.checkStreamabilityFix(resourceId).flatMap:
          case Left(error) => IO.pure(Left(error))
          case Right(())   =>
            val command = FixStreamability(bucketId, resourceId)
            bus.submit(command.key, command).map(Right(_))
      case _                                  => IO.pure(Left(ResourceCommandError.NotFound))

  /** Streams the request body to the bucket's temp directory, then queues the processing as a command. */
  def submitUpload(bucketId: BucketId, userId: UserId, fileName: String, source: fs2.Stream[IO, Byte]): IO[Either[UploadError, CommandId]] =
    registry.get(bucketId).flatMap:
      case Some(bucket: LocalDirectoryBucket) =>
        bucket.stageUpload(fileName, source).flatMap:
          case Left(error)   => IO.pure(Left(error))
          case Right(staged) =>
            val command = UploadResource(bucketId, staged.resourceId, userId, staged.fileName, staged.tempFile)
            bus
              .submit(command.key, command)
              .map(Right(_))
              .handleErrorWith(error => bucket.discardUpload(staged.tempFile) >> IO.raiseError(error))
      case _                                  => IO.pure(Left(UploadError.StorageError(s"Bucket '$bucketId' does not support uploads")))

  def status(id: CommandId): IO[Option[CommandStatus]] = bus.status(id)

  def processAtLeastOnce: Stream[IO, Unit] =
    bus.processAtLeastOnce(work)(commit)

  /** Slow and non-transactional: remuxes streamability or prepares an upload, all inside the bucket's temp directory. */
  private def work(command: ResourceCommand): IO[Either[String, Prepared]] =
    command match
      case FixStreamability(bucketId, resourceId) =>
        (for
          bucket   <- EitherT(localBucket(bucketId))
          tempFile <- EitherT(bucket.remuxForStreaming(resourceId)).leftMap(_.message)
        yield Prepared.Streamability(tempFile)).value
      case upload: UploadResource                 =>
        (for
          bucket <- EitherT(localBucket(upload.bucketId))
          value  <- EitherT(bucket.prepareUpload(upload)).leftMap(_.message)
        yield Prepared.Upload(value)).value

  /** Fast and transactional: persists the result together with the command's success. */
  private def commit(session: Session[IO], command: ResourceCommand, prepared: Prepared): IO[Either[String, Unit]] =
    (command, prepared) match
      case (FixStreamability(bucketId, resourceId), Prepared.Streamability(tempFile)) =>
        (for
          bucket <- EitherT(localBucket(bucketId))
          _      <- EitherT(bucket.commitStreamabilityFix(session, resourceId, tempFile)).leftMap(_.message)
        yield ()).value
      case (upload: UploadResource, Prepared.Upload(value))                           =>
        (for
          bucket <- EitherT(localBucket(upload.bucketId))
          _      <- EitherT(bucket.commitUpload(session, upload, value)).leftMap(_.message)
        yield ()).value
      case _                                                                          =>
        IO.pure(Left("Unexpected resource command preparation"))

  private def localBucket(bucketId: BucketId): IO[Either[String, LocalDirectoryBucket]] =
    registry.get(bucketId).map:
      case Some(bucket: LocalDirectoryBucket) => Right(bucket)
      case _                                  => Left(s"No local bucket '$bucketId'")

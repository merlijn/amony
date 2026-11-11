package nl.amony.modules.resources.local

import cats.effect.IO
import cats.syntax.all.*
import fs2.Stream
import skunk.Session

import nl.amony.modules.auth.api.UserId
import nl.amony.modules.resources.api.*
import nl.amony.platform.messagebus.{CommandBus, CommandId, CommandStatus, PermanentCommandFailure}

/** Result of the cheap pre-check done by the request, before any remux work is queued. */
enum StreamabilityFixCheck:
  case Fixable
  case Missing
  case Rejected(message: String)

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
          case StreamabilityFixCheck.Missing           => IO.pure(Left(ResourceCommandError.NotFound))
          case StreamabilityFixCheck.Rejected(message) => IO.pure(Left(ResourceCommandError.Rejected(message)))
          case StreamabilityFixCheck.Fixable           =>
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

  def processAtLeastOnce(consumerId: String): Stream[IO, Unit] =
    bus.processAtLeastOnce(consumerId)(work)(commit)

  /** Slow and non-transactional: remuxes streamability or prepares an upload, all inside the bucket's temp directory. */
  private def work(command: ResourceCommand): IO[Prepared] =
    command match
      case FixStreamability(bucketId, resourceId) =>
        localBucket(bucketId)
          .flatMap(_.remuxForStreaming(resourceId))
          .map(tempFile => Prepared.Streamability(tempFile))
          .adaptError {
            case error: UnsupportedStreamability => new PermanentCommandFailure(error.getMessage, error)
            case error: StreamabilityFixFailed   => new PermanentCommandFailure(error.getMessage, error)
          }
      case upload: UploadResource                 =>
        localBucket(upload.bucketId)
          .flatMap(_.prepareUpload(upload))
          .map(value => Prepared.Upload(value))
          .adaptError { case error: UploadProcessingFailed => new PermanentCommandFailure(error.getMessage, error) }

  /** Fast and transactional: persists the result together with the command's success. */
  private def commit(session: Session[IO], command: ResourceCommand, prepared: Prepared): IO[Unit] =
    (command, prepared) match
      case (FixStreamability(bucketId, resourceId), Prepared.Streamability(tempFile)) =>
        localBucket(bucketId)
          .flatMap(_.commitStreamabilityFix(session, resourceId, tempFile))
          .adaptError { case error: StreamabilityFixFailed => new PermanentCommandFailure(error.getMessage, error) }
      case (upload: UploadResource, Prepared.Upload(value))                           =>
        localBucket(upload.bucketId)
          .flatMap(_.commitUpload(session, upload, value))
          .adaptError { case error: UploadProcessingFailed => new PermanentCommandFailure(error.getMessage, error) }
      case _                                                                          =>
        IO.raiseError(new PermanentCommandFailure("Unexpected resource command preparation"))

  private def localBucket(bucketId: BucketId): IO[LocalDirectoryBucket] =
    registry.get(bucketId).flatMap:
      case Some(bucket: LocalDirectoryBucket) => IO.pure(bucket)
      case _                                  => IO.raiseError(new PermanentCommandFailure(s"No local bucket '$bucketId'"))

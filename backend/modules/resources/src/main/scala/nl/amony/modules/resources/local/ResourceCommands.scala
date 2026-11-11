package nl.amony.modules.resources.local

import cats.effect.IO
import cats.syntax.all.*
import fs2.Stream
import skunk.Session

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

  def status(id: CommandId): IO[Option[CommandStatus]] = bus.status(id)

  def processAtLeastOnce(consumerId: String): Stream[IO, Unit] =
    bus.processAtLeastOnce(consumerId)(work)(commit)

  /** Slow and non-transactional: remuxes to the bucket's temp directory. Retried by the command bus on transient errors. */
  private def work(command: ResourceCommand): IO[Option[String]] =
    command match
      case FixStreamability(bucketId, resourceId) =>
        localBucket(bucketId)
          .flatMap(_.remuxForStreaming(resourceId))
          .adaptError {
            case error: UnsupportedStreamability => new PermanentCommandFailure(error.getMessage, error)
            case error: StreamabilityFixFailed   => new PermanentCommandFailure(error.getMessage, error)
          }

  /** Fast and transactional: replaces the live file and persists the result together with the command's success. */
  private def commit(session: Session[IO], command: ResourceCommand, tempFile: Option[String]): IO[Unit] =
    command match
      case FixStreamability(bucketId, resourceId) =>
        localBucket(bucketId)
          .flatMap(_.commitStreamabilityFix(session, resourceId, tempFile))
          .adaptError { case error: StreamabilityFixFailed => new PermanentCommandFailure(error.getMessage, error) }

  private def localBucket(bucketId: BucketId): IO[LocalDirectoryBucket] =
    registry.get(bucketId).flatMap:
      case Some(bucket: LocalDirectoryBucket) => IO.pure(bucket)
      case _                                  => IO.raiseError(new PermanentCommandFailure(s"No local bucket '$bucketId'"))

package nl.amony.modules.resources.local

import cats.effect.IO
import cats.syntax.all.*
import fs2.Stream
import skunk.Session

import nl.amony.modules.resources.api.*
import nl.amony.platform.messagebus.{CommandBus, CommandId, CommandStatus}

enum StreamabilityFixPreparation:
  case Prepared(tempFile: String)
  case NotApplicable
  case Missing

enum ResourceCommandError:
  case NotFound
  case Rejected(message: String)

/** Submits resource commands and processes them, so the routes and the consumer share the same wiring. */
final class ResourceCommands(registry: BucketRegistry, bus: CommandBus[ResourceCommand]):

  def submitFixStreamability(bucketId: BucketId, resourceId: ResourceId): IO[Either[ResourceCommandError, CommandId]] =
    registry.get(bucketId).flatMap:
      case Some(bucket: LocalDirectoryBucket) =>
        bucket.prepareStreamabilityFix(resourceId).attempt.flatMap:
          case Left(_: UnsupportedStreamability)                     => rejected("The container format cannot be remuxed for streaming")
          case Left(error: StreamabilityFixFailed)                   => rejected(error.getMessage)
          case Left(error)                                           => IO.raiseError(error)
          case Right(StreamabilityFixPreparation.Missing)            => IO.pure(Left(ResourceCommandError.NotFound))
          case Right(StreamabilityFixPreparation.NotApplicable)      => rejected("Resource is already streamable or its streamability is unknown")
          case Right(StreamabilityFixPreparation.Prepared(tempFile)) =>
            val command = FixStreamability(bucketId, resourceId, Some(tempFile))
            bus.submit(command.key, command).map(Right(_))
      case _                                  => IO.pure(Left(ResourceCommandError.NotFound))

  private def rejected(message: String): IO[Either[ResourceCommandError, CommandId]] =
    IO.pure(Left(ResourceCommandError.Rejected(message)))

  def status(id: CommandId): IO[Option[CommandStatus]] = bus.status(id)

  def processAtLeastOnce(consumerId: String): Stream[IO, Unit] =
    bus.processAtLeastOnce(consumerId)(handle)

  private def handle(session: Session[IO], command: ResourceCommand): IO[Unit] =
    registry.get(command.bucketId).flatMap:
      case Some(bucket: LocalDirectoryBucket) =>
        command match
          case FixStreamability(_, resourceId, tempFile) => bucket.commitStreamabilityFix(session, resourceId, tempFile)
      case _                                  =>
        IO.raiseError(new IllegalStateException(s"No local bucket '${command.bucketId}' for command '${command.key}'"))

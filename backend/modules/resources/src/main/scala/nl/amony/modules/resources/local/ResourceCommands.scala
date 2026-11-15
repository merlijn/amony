package nl.amony.modules.resources.local

import cats.data.EitherT
import cats.effect.IO
import cats.syntax.all.*
import fs2.Stream
import skunk.Session

import nl.amony.modules.resources.api.*
import nl.amony.platform.messagebus.CommandBus

private[local] enum Prepared:
  case Streamability(tempFile: Option[String])
  case Upload(value: PreparedUpload)

/** Processes resource commands from the durable command bus. */
final class ResourceCommands(bus: CommandBus[ResourceCommand]):

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

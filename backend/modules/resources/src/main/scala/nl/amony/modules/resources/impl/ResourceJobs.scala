package nl.amony.modules.resources.impl

import cats.data.EitherT
import cats.effect.IO
import fs2.Stream

import nl.amony.modules.resources.api.*
import nl.amony.platform.messagebus.JobBus

/** Processes resource jobs from the durable job bus. */
final class ResourceJobs(bus: JobBus[ResourceJob]):

  def processAtLeastOnce(registry: BucketRegistry): Stream[IO, Unit] =
    bus.processAtLeastOnce(work(registry))

  /**
   * Slow preparation inside the bucket's temp directory, returning the fast commit action that moves the file into
   * place and persists the resource update.
   */
  private def work(registry: BucketRegistry)(job: ResourceJob): IO[Either[String, IO[Either[String, Unit]]]] =
    job match
      case FixStreamability(bucketId, resourceId) =>
        (for
          bucket   <- EitherT(getBucket(registry, bucketId))
          tempFile <- EitherT(bucket.remuxForStreaming(resourceId)).leftMap(_.message)
        yield bucket.commitStreamabilityFix(resourceId, tempFile).map(_.left.map(_.message))).value

      case upload: UploadResource =>
        (for
          bucket <- EitherT(getBucket(registry, upload.bucketId))
          value  <- EitherT(bucket.prepareUpload(upload)).leftMap(_.message)
        yield bucket.commitUpload(upload, value).map(_.left.map(_.message))).value

  private def getBucket(registry: BucketRegistry, bucketId: BucketId): IO[Either[String, ResourceBucket]] =
    registry.get(bucketId).map(_.toRight(s"No bucket '$bucketId'"))

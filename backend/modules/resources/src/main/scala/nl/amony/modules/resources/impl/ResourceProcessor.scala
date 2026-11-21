package nl.amony.modules.resources.impl

import cats.data.EitherT
import cats.effect.IO
import scribe.Logging
import nl.amony.modules.resources.api.*

class ResourceProcessor(registry: BucketRegistry) extends Logging:

  def processEvent(event: ResourceEvent): IO[Unit] = event match
    case ResourceAdded(resource)                                                                       => withBucket(resource)(_.generatePreviewsIfEnabled(resource))
    case ResourceModified(resource)                                                                    => withBucket(resource)(_.generatePreviewsIfEnabled(resource))
    case ResourceThumbnailUpdated(_, resource)                                                         => withBucket(resource)(_.generatePreviewsIfEnabled(resource))
    case ResourceMovedAndModified(_, _, _, resource)                                                   => withBucket(resource)(_.generatePreviewsIfEnabled(resource))
    case ResourceDeleted(resource)                                                                     => withBucket(resource)(_.deleteFile(resource))
    case _: ResourceUserMetaUpdated | _: ResourceMoved | _: ResourceFileMetaChanged | _: BucketDeleted => IO.unit

  private def withBucket(resource: ResourceInfo)(f: ResourceBucket => IO[Unit]): IO[Unit] =
    registry.get(resource.bucketId).flatMap {
      case Some(bucket) => f(bucket)
      case None         => IO.unit
    }

  def processJob(job: ResourceJob): IO[Either[String, IO[Either[String, Unit]]]] =
    job match
      case FixStreamability(bucketId, resourceId) =>
        (for
          bucket <- EitherT(getBucket(registry, bucketId))
          stagingId <- EitherT(bucket.processStreamabilityFix(resourceId)).leftMap(_.message)
        yield bucket.commitStreamabilityFix(resourceId, stagingId).map(_.left.map(_.message))).value

      case upload: UploadResource =>
        (for
          bucket <- EitherT(getBucket(registry, upload.bucketId))
          value <- EitherT(bucket.processUpload(upload)).leftMap(_.message)
        yield bucket.commitUpload(upload, value).map(_.left.map(_.message))).value

  private def getBucket(registry: BucketRegistry, bucketId: BucketId): IO[Either[String, ResourceBucket]] =
    registry.get(bucketId).map(_.toRight(s"No bucket '$bucketId'"))  

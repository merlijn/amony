package nl.amony.modules.resources.local

import cats.effect.IO
import scribe.Logging

import nl.amony.modules.resources.api.*

class ResourceProcessor(registry: BucketRegistry) extends Logging:

  def processEvent(event: ResourceEvent): IO[Unit] = event match
    case ResourceAdded(resource)                                                                       => withLocalBucket(resource)(_.generatePreviewsIfEnabled(resource))
    case ResourceModified(resource)                                                                    => withLocalBucket(resource)(_.generatePreviewsIfEnabled(resource))
    case ResourceThumbnailUpdated(_, resource)                                                         => withLocalBucket(resource)(_.generatePreviewsIfEnabled(resource))
    case ResourceMovedAndModified(_, _, _, resource)                                                   => withLocalBucket(resource)(_.generatePreviewsIfEnabled(resource))
    case ResourceDeleted(resource)                                                                     => withLocalBucket(resource)(_.deleteFile(resource))
    case _: ResourceUserMetaUpdated | _: ResourceMoved | _: ResourceFileMetaChanged | _: BucketDeleted => IO.unit

  private def withLocalBucket(resource: ResourceInfo)(f: LocalDirectoryBucket => IO[Unit]): IO[Unit] =
    registry.get(resource.bucketId).flatMap {
      case Some(bucket: LocalDirectoryBucket) => f(bucket)
      case _                                  => IO.unit
    }

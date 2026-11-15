package nl.amony.modules.resources.local

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

package nl.amony.modules.resources.local

import cats.effect.IO
import scribe.Logging

import nl.amony.modules.resources.api.BucketRegistry
import nl.amony.modules.resources.api.ResourceAdded
import nl.amony.modules.resources.api.ResourceEvent
import nl.amony.modules.resources.api.ResourceInfo
import nl.amony.modules.resources.api.ResourceMovedAndModified
import nl.amony.modules.resources.api.ResourceUpdated

/** Generates previews asynchronously by consuming resource events, similar to the Solr indexer. */
class PreviewGenerator(registry: BucketRegistry) extends Logging:

  def processEvent(event: ResourceEvent): IO[Unit] = event match
    case ResourceAdded(resource)                     => generate(resource)
    case ResourceUpdated(resource)                   => generate(resource)
    case ResourceMovedAndModified(_, _, _, resource) => generate(resource)
    case _                                           => IO.unit

  private def generate(resource: ResourceInfo): IO[Unit] =
    registry.get(resource.bucketId).flatMap {
      case Some(bucket: LocalDirectoryBucket) => bucket.generatePreviewsIfEnabled(resource)
      case _                                  => IO.unit
    }

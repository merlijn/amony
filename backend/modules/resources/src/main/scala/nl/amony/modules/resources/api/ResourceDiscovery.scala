package nl.amony.modules.resources.api

import cats.effect.IO

/** Re-lists the backing storage of a bucket and applies the differences. */
trait ResourceDiscovery:

  /** Performs a single scan of the bucket's storage. */
  def refresh(): IO[Unit]

/** Looks up the discovery process of a bucket. */
trait BucketDiscoveryRegistry:
  def getDiscovery(bucketId: BucketId): IO[Option[ResourceDiscovery]]

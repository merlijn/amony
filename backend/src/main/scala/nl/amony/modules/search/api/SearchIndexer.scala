package nl.amony.modules.search.api

import cats.effect.IO

import nl.amony.modules.resources.api.{BucketId, ResourceEvent, ResourceInfo}

/** The write side of the search index, driven by the message bus consumer and the admin re-index endpoints. */
trait SearchIndexer:

  /** Applies a resource event to the index; used by the message bus consumer. */
  def processEvent(event: ResourceEvent): IO[Unit]
  def indexAll(resources: fs2.Stream[IO, ResourceInfo]): IO[Unit]
  def index(resource: ResourceInfo): IO[Unit]
  def deleteBucket(bucketId: BucketId): IO[Unit]
  def forceCommit(): IO[Unit]

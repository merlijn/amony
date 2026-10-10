package nl.amony.modules.search.api

import cats.effect.IO

import nl.amony.modules.resources.api.{BucketId, ResourceEvent, ResourceInfo}

trait SearchService:
  def deleteBucket(bucketId: BucketId): IO[Unit]

  /** Applies a resource event to the index; used by the message bus consumer. */
  def processEvent(event: ResourceEvent): IO[Unit]
  def searchMedia(query: Query): IO[SearchResult]

  /** All resources matching `query`, transparently paging through the result set. */
  def searchAll(query: Query): fs2.Stream[IO, ResourceInfo]
  def indexAll(resources: fs2.Stream[IO, ResourceInfo]): IO[Unit]
  def index(resource: ResourceInfo): IO[Unit]
  def forceCommit(): IO[Unit]

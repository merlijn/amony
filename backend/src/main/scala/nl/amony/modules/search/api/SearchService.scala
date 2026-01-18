package nl.amony.modules.search.api

import cats.effect.IO

import nl.amony.modules.resources.api.ResourceInfo

trait SearchService:
  def deleteBucket(bucketId: String): IO[Unit]
  def searchMedia(query: Query): IO[SearchResult]
  def indexAll(resources: fs2.Stream[IO, ResourceInfo]): IO[Unit]
  def index(resource: ResourceInfo): IO[Unit]
  def forceCommit(): IO[Unit]

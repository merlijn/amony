package nl.amony.service.search.domain

import cats.effect.IO

import nl.amony.service.resources.domain.ResourceInfo

trait SearchService:
  def deleteBucket(bucketId: String): IO[Unit]
  def searchMedia(query: Query): IO[SearchResult]
  def indexAll(resources: fs2.Stream[IO, ResourceInfo]): IO[Unit]
  def index(resource: ResourceInfo): IO[Unit]
  def forceCommit(): IO[Unit]

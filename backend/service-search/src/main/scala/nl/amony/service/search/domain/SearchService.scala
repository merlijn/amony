package nl.amony.service.search.domain

import cats.effect.IO
import nl.amony.service.resources.domain.ResourceInfo

trait SearchService:
  def deleteBucket(request: DeleteBucketRequest): IO[DeleteBucketResult]
  def searchMedia(query: Query): IO[SearchResult]
  def indexAll(resources: fs2.Stream[IO, ResourceInfo]): IO[ReIndexResult]
  def index(request: ResourceInfo): IO[ReIndexResult]
  def forceCommit(request: ForceCommitRequest): IO[ForceCommitResult]

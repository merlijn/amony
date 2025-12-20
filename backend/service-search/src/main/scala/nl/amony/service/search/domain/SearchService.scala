package nl.amony.service.search.domain

import cats.effect.IO
import nl.amony.service.resources.domain.ResourceInfo

import scala.concurrent.Future

trait SearchService:
  def deleteBucket(request: DeleteBucketRequest): Future[DeleteBucketResult]
  def searchMedia(query: Query): Future[SearchResult]
  def indexAll(resources: fs2.Stream[IO, ResourceInfo]): IO[ReIndexResult]
  def index(request: ResourceInfo): Future[ReIndexResult]
  def forceCommit(request: ForceCommitRequest): Future[ForceCommitResult]

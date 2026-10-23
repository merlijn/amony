package nl.amony.modules.search.api

import cats.effect.IO

import nl.amony.modules.resources.api.ResourceInfo

/** The read side of the search index. */
trait SearchService:
  def searchMedia(query: Query): IO[SearchResult]

  /** All resources matching `query`, transparently paging through the result set. */
  def searchAll(query: Query): fs2.Stream[IO, ResourceInfo]

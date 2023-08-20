package nl.amony.search

import nl.amony.service.resources.api.ResourceInfo
import nl.amony.service.resources.api.events.{ResourceAdded, ResourceDeleted, ResourceEvent, ResourceMoved, ResourceUserMetaUpdated}
import nl.amony.service.resources.web.{durationInMillis, fileName, height}
import nl.amony.service.search.api.SearchServiceGrpc.SearchService
import nl.amony.service.search.api.SortDirection.{Asc, Desc}
import nl.amony.service.search.api.SortField.*
import nl.amony.service.search.api.{Query, SearchResult, SortOption}
import scribe.Logging

import scala.concurrent.Future

/**
 * Proof of concept in memory search services.
 *
 * Note:
 *
 * This was not written with performance in mind.
 * It does not scale and will become slow quickly.
 * May be suitable up to a few thousand entries but this is untested.
 *
 */
class InMemorySearchService extends SearchService with Logging {

    private var counter: Long = 0L
    private var indexedAt: Long = 0L
    private var resourceIndex: Map[String, ResourceInfo] = Map.empty
    private var sortedByTitle: Vector[ResourceInfo] = Vector.empty
    private var sortedByDateAdded: Vector[ResourceInfo] = Vector.empty
    private var sortedByDuration: Vector[ResourceInfo] = Vector.empty
    private var sortedBySize: Vector[ResourceInfo] = Vector.empty
    var tags: Set[String] = Set.empty

    private def updateIndex(): Unit = {

      if (indexedAt < counter) {
        synchronized {
          logger.debug("Updating index")
          sortedByTitle = resourceIndex.values.toVector.sortBy(_.fileName())
          sortedByDateAdded = resourceIndex.values.toVector.sortBy(_.getCreationTime)
          sortedByDuration = resourceIndex.values.toVector.sortBy(_.durationInMillis())
          sortedBySize = resourceIndex.values.toVector.sortBy(_.size)
          tags = resourceIndex.values.flatMap(_.tags).toSet
          indexedAt = counter
        }
      }
    }

    def indexEvent(e: ResourceEvent) = {
      synchronized {
//        logger.debug(s"Applying event: $e")
        e match {

          case ResourceAdded(resource) =>
            resourceIndex += resource.hash -> resource

          case ResourceDeleted(resource) =>
            resourceIndex -= resource.hash

          case ResourceMoved(resource, _) =>
            resourceIndex += resource.hash -> resource

          case ResourceUserMetaUpdated(bucketId, resourceId, title, description, deletedTags, newTags) =>
            val resource = resourceIndex(resourceId)
            val newResource = resource.copy(
              title = title.orElse(resource.title),
              description = description.orElse(resource.description),
            )

            resourceIndex += resourceId -> newResource
        }

        counter += 1
      }
    }

    override def searchMedia(query: Query): Future[SearchResult] = {
      updateIndex()

      def filterRes(m: ResourceInfo): Boolean = query.minRes.map(res => m.height >= res).getOrElse(true)

      def filterQuery(m: ResourceInfo): Boolean =
        query.q.map(q => m.path.toLowerCase.contains(q.toLowerCase)).getOrElse(true)

      def filterTag(m: ResourceInfo): Boolean =
        query.tags.forall(tag => m.tags.contains(tag))

      def filterDuration(m: ResourceInfo): Boolean = {
        (query.minDuration, query.maxDuration) match {
          case (Some(min), Some(max)) => m.durationInMillis() >= min && m.durationInMillis() <= max
          case _ => true
        }
      }

      def filterMedia(m: ResourceInfo): Boolean = filterRes(m) && filterQuery(m) && filterTag(m) && filterDuration(m)

      val unfiltered = query.sort match {
        case Some(SortOption(Title, Asc)) => sortedByTitle
        case Some(SortOption(Title, Desc)) => sortedByTitle.reverse
        case Some(SortOption(DateAdded, Asc)) => sortedByDateAdded
        case Some(SortOption(DateAdded, Desc)) => sortedByDateAdded.reverse
        case Some(SortOption(Duration, Asc)) => sortedByDuration
        case Some(SortOption(Duration, Desc)) => sortedByDuration.reverse
        case Some(SortOption(Size, Asc)) => sortedBySize
        case Some(SortOption(Size, Desc)) => sortedBySize.reverse
        case _ => resourceIndex.values
      }

      val result = unfiltered.filter(filterMedia)

      val offset = query.offset.getOrElse(0)
      val end = Math.min(offset + query.n, result.size)

      val videos = if (offset > result.size) Nil else result.slice(offset, end)

      Future.successful(SearchResult(offset, result.size, videos.toList, tags.toList))
    }
}

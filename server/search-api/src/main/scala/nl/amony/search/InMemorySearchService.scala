package nl.amony.search

import nl.amony.service.fragments.FragmentProtocol
import nl.amony.service.media.MediaEvents
import nl.amony.service.media.api.Media
import nl.amony.service.search.api.SortDirection.{Asc, Desc}
import nl.amony.service.search.api.SortField._
import nl.amony.service.search.api.{Query, SearchResult, SortOption}
import scribe.Logging

import scala.concurrent.Future

class InMemorySearchService extends SearchService with Logging {

    var counter: Long = 0L
    var indexedAt: Long = 0L
    var state: Map[String, Media] = Map.empty
    var sortedByTitle: Vector[Media] = Vector.empty
    var sortedByDateAdded: Vector[Media] = Vector.empty
    var sortedByDuration: Vector[Media] = Vector.empty
    var sortedBySize: Vector[Media] = Vector.empty
    var tags: Set[String] = Set.empty

    def media: Map[String, Media] = state

    def updateIndex(): Unit = {

      if (indexedAt < counter) {
        synchronized {
          logger.debug("Updating index")
          sortedByTitle = media.values.toVector.sortBy(m => m.meta.title.getOrElse(m.fileName()))
          sortedByDateAdded = media.values.toVector.sortBy(_.createdTimestamp)
          sortedByDuration = media.values.toVector.sortBy(_.mediaInfo.durationInMillis)
          sortedBySize = media.values.toVector.sortBy(_.resourceInfo.sizeInBytes)
          tags = media.values.flatMap(_.meta.tags).toSet
          indexedAt = counter
        }
      }
    }

    def update(e: MediaEvents.Event) = {
      synchronized {
        state = MediaEvents.apply(state, e)
        counter += 1
      }
    }

    def getTags() = {
      updateIndex()
      tags
    }

    def searchFragments(size: Int, offset: Int, maybeTag: String) = {
      updateIndex()
    }

    def search(query: Query): SearchResult = {
      updateIndex()

      def filterRes(m: Media): Boolean = query.minRes.map(res => m.height >= res).getOrElse(true)

      def filterQuery(m: Media): Boolean =
        query.q.map(q => m.resourceInfo.relativePath.toLowerCase.contains(q.toLowerCase)).getOrElse(true)

      def filterTag(m: Media): Boolean =
        query.tags.forall(tag => m.meta.tags.contains(tag))

      def filterDuration(m: Media): Boolean = {
        (query.minDuration, query.maxDuration) match {
          case (Some(min), Some(max)) => m.mediaInfo.durationInMillis >= min && m.mediaInfo.durationInMillis <= max
          case _ => true
        }
      }

      def filterMedia(m: Media): Boolean = filterRes(m) && filterQuery(m) && filterTag(m) && filterDuration(m)

      val unfiltered = query.sort match {
        case None => state.values
        case Some(SortOption(Title, Asc)) => sortedByTitle
        case Some(SortOption(Title, Desc)) => sortedByTitle.reverse
        case Some(SortOption(DateAdded, Asc)) => sortedByDateAdded
        case Some(SortOption(DateAdded, Desc)) => sortedByDateAdded.reverse
        case Some(SortOption(Duration, Asc)) => sortedByDuration
        case Some(SortOption(Duration, Desc)) => sortedByDuration.reverse
        case Some(SortOption(Size, Asc)) => sortedBySize
        case Some(SortOption(Size, Desc)) => sortedBySize.reverse
      }

      val result = unfiltered.filter(filterMedia)

      val offset = query.offset.getOrElse(0)
      val end = Math.min(offset + query.n, result.size)

      val videos = if (offset > result.size) Nil else result.slice(offset, end)

      SearchResult(offset, result.size, videos.toList, List.empty)
    }

  override def searchMedia(q: Option[String], offset: Option[Int], size: Int, tags: Seq[String], playlist: Option[String], minRes: Option[Int], duration: Option[(Long, Long)], sort: SortOption): Future[SearchResult] =
    Future.successful(search(Query(q, offset, size, tags, playlist, minRes, duration.map(_._1), duration.map(_._2), Some(sort))))

  override def searchTags(): Future[Set[String]] = Future.successful(tags)

  override def searchFragments(size: Int, offset: Int, tag: Option[String]): Future[Seq[FragmentProtocol.Fragment]] = ???
}

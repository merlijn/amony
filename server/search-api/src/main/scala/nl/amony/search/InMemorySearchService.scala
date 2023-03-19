package nl.amony.search

import nl.amony.service.media.api.{Media, MediaMeta}
import nl.amony.service.media.api.events._
import nl.amony.service.search.api.SearchServiceGrpc.SearchService
import nl.amony.service.search.api.SortDirection.{Asc, Desc}
import nl.amony.service.search.api.SortField._
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
    private var mediaIndex: Map[String, Media] = Map.empty
    private var sortedByTitle: Vector[Media] = Vector.empty
    private var sortedByDateAdded: Vector[Media] = Vector.empty
    private var sortedByDuration: Vector[Media] = Vector.empty
    private var sortedBySize: Vector[Media] = Vector.empty
    var tags: Set[String] = Set.empty

    private def updateIndex(): Unit = {

      if (indexedAt < counter) {
        synchronized {
          logger.debug("Updating index")
          sortedByTitle = mediaIndex.values.toVector.sortBy(m => m.meta.title.getOrElse(m.fileName()))
          sortedByDateAdded = mediaIndex.values.toVector.sortBy(_.createdTimestamp)
          sortedByDuration = mediaIndex.values.toVector.sortBy(_.mediaInfo.durationInMillis)
          sortedBySize = mediaIndex.values.toVector.sortBy(_.resourceInfo.sizeInBytes)
          tags = mediaIndex.values.flatMap(_.meta.tags).toSet
          indexedAt = counter
        }
      }
    }

    def indexEvent(e: MediaEvent) = {
      synchronized {
        logger.debug(s"Applying event: $e")
        e match {

          case MediaAdded(media) =>
            mediaIndex += (media.mediaId -> media)

          case MediaUpdated(media) =>
            mediaIndex += (media.mediaId -> media)

          case MediaMetaDataUpdated(mediaId, title, comment, tagsAdded, tagsRemoved) =>
            val media = mediaIndex(mediaId)

            val newMeta = MediaMeta(
              title = title.orElse(media.meta.title),
              comment = comment.orElse(media.meta.comment),
              tags = (media.meta.tags.toSet -- tagsRemoved ++ tagsAdded).toSeq.sortBy(media.meta.tags.indexOf)
            )

            mediaIndex += (media.mediaId -> media.copy(meta = newMeta))

          case MediaRemoved(id) =>
            mediaIndex -= id
        }

        counter += 1
      }
    }

    override def searchMedia(query: Query): Future[SearchResult] = {
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
        case Some(SortOption(Title, Asc)) => sortedByTitle
        case Some(SortOption(Title, Desc)) => sortedByTitle.reverse
        case Some(SortOption(DateAdded, Asc)) => sortedByDateAdded
        case Some(SortOption(DateAdded, Desc)) => sortedByDateAdded.reverse
        case Some(SortOption(Duration, Asc)) => sortedByDuration
        case Some(SortOption(Duration, Desc)) => sortedByDuration.reverse
        case Some(SortOption(Size, Asc)) => sortedBySize
        case Some(SortOption(Size, Desc)) => sortedBySize.reverse
        case _ => mediaIndex.values
      }

      val result = unfiltered.filter(filterMedia)

      val offset = query.offset.getOrElse(0)
      val end = Math.min(offset + query.n, result.size)

      val videos = if (offset > result.size) Nil else result.slice(offset, end)

      Future.successful(SearchResult(offset, result.size, videos.toList, tags.toList))
    }
}

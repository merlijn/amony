package nl.amony.search

import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.{Actor, ActorRef, Props}
import akka.stream.Materializer
import nl.amony.search.SearchProtocol._
import nl.amony.service.media.MediaEvents
import nl.amony.service.media.MediaProtocol.Media
import scribe.Logging

object InMemoryIndex {

  def apply[T](context: ActorContext[T])(implicit mat: Materializer): ActorRef = {

    import akka.actor.typed.scaladsl.adapter._

    val indexActor: ActorRef = context.actorOf(Props(new LocalIndexActor()), "index")

    context.system.receptionist ! Receptionist.Register(QueryMessage.serviceKey, indexActor.toTyped[QueryMessage])

    indexActor
  }

  class LocalIndexActor() extends Actor with Logging {

    var counter: Long = 0L
    var indexedAt: Long = 0L
    var state: Map[String, Media] = Map.empty
    var sortedByTitle: Vector[Media] = Vector.empty
    var sortedByDateAdded: Vector[Media] = Vector.empty
    var sortedByDuration: Vector[Media] = Vector.empty
    var sortedBySize: Vector[Media] = Vector.empty
    var tags: Set[String] = Set.empty

    def media: Map[String, Media] = state

    def updateIndex() = {

      if (indexedAt < counter) {
        logger.debug("Updating index")
        sortedByTitle     = media.values.toVector.sortBy(m => m.meta.title.getOrElse(m.fileName()))
        sortedByDateAdded = media.values.toVector.sortBy(_.uploadTimestamp)
        sortedByDuration  = media.values.toVector.sortBy(_.mediaInfo.duration)
        sortedBySize      = media.values.toVector.sortBy(_.resourceInfo.sizeInBytes)
        tags              = media.values.flatMap(_.meta.tags).toSet
        indexedAt         = counter
      }
    }

    override def receive: Receive = {

      case e: MediaEvents.Event =>
        state = MediaEvents.apply(state, e)
        counter += 1

      case GetTags(sender) =>
        updateIndex()
        sender.tell(tags)

      case SearchFragments(size, offset, maybeTag, sender) =>
        updateIndex()

//        val results = state.media.values
//          .flatMap(m => m.highlights)
//          .filter { f =>
//            maybeTag.map(tag => f.tags.contains(tag)).getOrElse(true)
//          }
//          .drop(offset)
//          .take(size)

        sender.tell(List.empty)

      case Search(query, sender) =>
        updateIndex()

        def filterRes(m: Media): Boolean = query.minRes.map(res => m.height >= res).getOrElse(true)
        def filterQuery(m: Media): Boolean =
          query.q.map(q => m.resourceInfo.relativePath.toLowerCase.contains(q.toLowerCase)).getOrElse(true)
        def filterTag(m: Media): Boolean =
          query.tags.forall(tag => m.meta.tags.contains(tag))
        def filterDuration(m: Media): Boolean =
          query.duration
            .map { case (min, max) => m.mediaInfo.duration >= min && m.mediaInfo.duration <= max }
            .getOrElse(true)
        def filterMedia(m: Media): Boolean = filterRes(m) && filterQuery(m) && filterTag(m) && filterDuration(m)

        val unfiltered = query.sort match {
          case None                        => state.values
          case Some(Sort(Title, Asc))      => sortedByTitle
          case Some(Sort(Title, Desc))     => sortedByTitle.reverse
          case Some(Sort(DateAdded, Asc))  => sortedByDateAdded
          case Some(Sort(DateAdded, Desc)) => sortedByDateAdded.reverse
          case Some(Sort(Duration, Asc))   => sortedByDuration
          case Some(Sort(Duration, Desc))  => sortedByDuration.reverse
          case Some(Sort(Size, Asc))       => sortedBySize
          case Some(Sort(Size, Desc))      => sortedBySize.reverse
        }

        val result = unfiltered.filter(filterMedia)

        val offset = query.offset.getOrElse(0)
        val end    = Math.min(offset + query.n, result.size)

        val videos = if (offset > result.size) Nil else result.slice(offset, end)

        sender.tell(SearchResult(offset, result.size, videos.toList, Map.empty))
    }
  }
}

package nl.amony.search

import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.{Actor, ActorRef, Props, typed}
import akka.persistence.query.PersistenceQuery
import akka.persistence.query.scaladsl.EventsByPersistenceIdQuery
import akka.stream.Materializer
import nl.amony.lib.akka.EventProcessing
import nl.amony.search.SearchProtocol._
import nl.amony.service.media.MediaService
import nl.amony.service.media.actor.MediaLibEventSourcing
import nl.amony.service.media.actor.MediaLibProtocol.{Media, State}
import scribe.Logging

object InMemoryIndex {

//  def apply[T](context: ActorContext[T])(implicit mat: Materializer): ActorRef = {
//
//    val readJournalId = context.system.settings.config.getString("akka.persistence.query.journal.plugin-id")
//    val readJournal = PersistenceQuery(context.system).readJournalFor[EventsByPersistenceIdQuery](readJournalId)
//
//    apply(context)
//  }

  def apply[T](context: ActorContext[T])(implicit mat: Materializer): ActorRef = {

    import akka.actor.typed.scaladsl.adapter._

    val indexActor: ActorRef = context.actorOf(Props(new LocalIndexActor()), "index")

    context.system.receptionist ! Receptionist.Register(QueryMessage.serviceKey, indexActor.toTyped[QueryMessage])

//    EventProcessing.processEvents[MediaLibEventSourcing.Event](MediaService.mediaPersistenceId, readJournal) { e =>
//      indexActor.tell(e, ActorRef.noSender)
//    }

    indexActor
  }

  class LocalIndexActor() extends Actor with Logging {

    var counter: Long = 0L
    var indexedAt: Long = 0L
    var state: State = State(Map.empty)
    var sortedByTitle: Vector[Media] = Vector.empty
    var sortedByDateAdded: Vector[Media] = Vector.empty
    var sortedByDuration: Vector[Media] = Vector.empty
    var sortedBySize: Vector[Media] = Vector.empty
    var tags: Set[String] = Set.empty

    def media: Map[String, Media] = state.media

    def updateIndex() = {

      if (indexedAt < counter) {
        logger.debug("Updating index")
        sortedByTitle     = media.values.toVector.sortBy(m => m.meta.title.getOrElse(m.fileName()))
        sortedByDateAdded = media.values.toVector.sortBy(_.uploadTimestamp)
        sortedByDuration  = media.values.toVector.sortBy(_.videoInfo.duration)
        sortedBySize      = media.values.toVector.sortBy(_.resourceInfo.size)
        tags              = media.values.flatMap(_.meta.tags).toSet
        indexedAt         = counter
      }
    }

    override def receive: Receive = {

      case e: MediaLibEventSourcing.Event =>
        state = MediaLibEventSourcing.apply(state, e)
        counter += 1

      case GetTags(sender) =>
        updateIndex()
        sender.tell(tags)

      case SearchFragments(size, offset, maybeTag, sender) =>
        updateIndex()

        val results = state.media.values
          .flatMap(m => m.highlights)
          .filter { f =>
            maybeTag.map(tag => f.tags.contains(tag)).getOrElse(true)
          }
          .drop(offset)
          .take(size)

        sender.tell(results.toSeq)

      case Search(query, sender) =>
        updateIndex()

        def filterRes(m: Media): Boolean = query.minRes.map(res => m.height >= res).getOrElse(true)
        def filterQuery(m: Media): Boolean =
          query.q.map(q => m.resourceInfo.relativePath.toLowerCase.contains(q.toLowerCase)).getOrElse(true)
        def filterTag(m: Media): Boolean =
          query.tags.forall(tag => m.meta.tags.contains(tag))
        def filterDuration(m: Media): Boolean =
          query.duration
            .map { case (min, max) => m.videoInfo.duration >= min && m.videoInfo.duration <= max }
            .getOrElse(true)
        def filterMedia(m: Media): Boolean = filterRes(m) && filterQuery(m) && filterTag(m) && filterDuration(m)

        val unfiltered = query.sort match {
          case None                        => state.media.values
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

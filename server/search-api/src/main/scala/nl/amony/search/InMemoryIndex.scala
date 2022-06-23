package nl.amony.search

import akka.Done
import akka.actor.typed.ActorSystem
import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.{Actor, ActorRef, Props, typed}
import akka.persistence.query.{EventEnvelope, PersistenceQuery}
import akka.persistence.query.scaladsl.EventsByPersistenceIdQuery
import akka.stream.Materializer
import nl.amony.service.media.actor.MediaLibProtocol.{Media, State}
import nl.amony.search.SearchProtocol._
import nl.amony.service.media.MediaService
import nl.amony.service.media.actor.MediaLibEventSourcing
import scribe.Logging

import scala.concurrent.Future

object InMemoryIndex {

  def apply[T](context: ActorContext[T])(implicit mat: Materializer): typed.ActorRef[QueryMessage] = {

    val readJournalId = context.system.settings.config.getString("amony.akka.read-journal")
    val readJournal = PersistenceQuery(context.system).readJournalFor[EventsByPersistenceIdQuery](readJournalId)

    apply(context, readJournal)
  }

  def apply[T](context: ActorContext[T], readJournal: EventsByPersistenceIdQuery)(implicit mat: Materializer): typed.ActorRef[QueryMessage] = {

    import akka.actor.typed.scaladsl.adapter._

    val indexActor = context.actorOf(Props(new LocalIndexActor()), "index")
    val typedRef = indexActor.toTyped[QueryMessage]

    context.system.receptionist ! Receptionist.Register(QueryMessage.serviceKey, typedRef)

    runIndex(indexActor, readJournal)

    typedRef
  }

  def runIndex[T](indexActor: ActorRef, readJournal: EventsByPersistenceIdQuery)(implicit mat: Materializer): Future[Done] = {

    readJournal.eventsByPersistenceId(MediaService.mediaPersistenceId, 0L, Long.MaxValue).runForeach {
      case EventEnvelope(_, _, _, e: MediaLibEventSourcing.Event) =>
        indexActor.tell(e, ActorRef.noSender)
    }
  }

  class LocalIndexActor() extends Actor with Logging {

    var counter: Long = 0L
    var indexedAt: Long = 0L
    var state: State = State(Map.empty)
    var sortedByTitle: List[Media] = List.empty
    var sortedByDateAdded: List[Media] = List.empty
    var sortedByDuration: List[Media] = List.empty
    var sortedBySize: List[Media] = List.empty
    var tags: Set[String] = Set.empty

    def media: Map[String, Media] = state.media

    def updateIndex() = {

      if (indexedAt < counter) {
        logger.debug("Updating index")
        sortedByTitle  = media.values.toList.sortBy(m => m.meta.title.getOrElse(m.fileName()))
        sortedByDateAdded = media.values.toList.sortBy(_.fileInfo.creationTime)
        sortedByDuration  = media.values.toList.sortBy(_.videoInfo.duration)
        sortedBySize      = media.values.toList.sortBy(_.fileInfo.size)
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
          .flatMap(m => m.fragments.map(f => m.id -> f))
          .filter { case (_, f) =>
            maybeTag.map(tag => f.tags.contains(tag)).getOrElse(true)
          }
          .drop(offset)
          .take(size)

        sender.tell(results.toSeq)

      case Search(query, sender) =>
        updateIndex()

        def filterRes(m: Media): Boolean = query.minRes.map(res => m.height >= res).getOrElse(true)
        def filterQuery(m: Media): Boolean =
          query.q.map(q => m.fileInfo.relativePath.toLowerCase.contains(q.toLowerCase)).getOrElse(true)
        def filterTag(m: Media): Boolean =
          query.tags.forall(tag => m.meta.tags.contains(tag))
        def filterDuration(m: Media): Boolean =
          query.duration
            .map { case (min, max) =>
              m.videoInfo.duration >= min && m.videoInfo.duration <= max
            }
            .getOrElse(true)
        def filterMedia(m: Media): Boolean = filterRes(m) && filterQuery(m) && filterTag(m) && filterDuration(m)

        val unfiltered = query.sort match {
          case None                        => state.media.values
          case Some(Sort(Title, Asc))   => sortedByTitle
          case Some(Sort(Title, Desc))  => sortedByTitle.reverse
          case Some(Sort(DateAdded, Asc))  => sortedByDateAdded
          case Some(Sort(DateAdded, Desc)) => sortedByDateAdded.reverse
          case Some(Sort(Duration, Asc))   => sortedByDuration
          case Some(Sort(Duration, Desc))  => sortedByDuration.reverse
          case Some(Sort(Size, Asc))   => sortedBySize
          case Some(Sort(Size, Desc))  => sortedBySize.reverse
        }

        val result = unfiltered.filter(filterMedia)

        val offset = query.offset.getOrElse(0)
        val end    = Math.min(offset + query.n, result.size)

        val videos = if (offset > result.size) Nil else result.slice(offset, end)

        sender.tell(SearchResult(offset, result.size, videos.toList, Map.empty))
    }
  }
}

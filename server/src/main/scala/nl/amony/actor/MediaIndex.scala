package nl.amony.actor

import akka.actor.typed
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.{Actor, ActorRef, Props}
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.persistence.query.{EventEnvelope, PersistenceQuery}
import akka.stream.Materializer
import better.files.File
import nl.amony.MediaLibConfig
import nl.amony.actor.MediaLibProtocol.{Media, State}
import akka.actor.typed.scaladsl.adapter._
import scribe.Logging

object MediaIndex {

  sealed trait IndexQuery extends Message

  case class Directory(id: String, path: String)
  case class GetDirectories(sender: typed.ActorRef[List[Directory]])    extends IndexQuery
  case class Search(query: Query, sender: typed.ActorRef[SearchResult]) extends IndexQuery

  sealed trait SortField
  case object FileName      extends SortField
  case object DateAdded     extends SortField
  case object VideoDuration extends SortField

  case class Sort(field: SortField, reverse: Boolean)
  case class Query(
                    q: Option[String],
                    offset: Option[Int],
                    n: Int,
                    directory: Option[String],
                    minRes: Option[Int],
                    sort: Option[Sort]
                  )
  case class SearchResult(offset: Int, total: Int, items: Seq[Media])

  def apply[T](config: MediaLibConfig, context: ActorContext[T])(implicit mat: Materializer): ActorRef = {

    import akka.actor.typed.scaladsl.adapter._

    val indexActor = context.actorOf(Props(new LocalIndexActor(config)), "index")

    val readJournal =
      PersistenceQuery(context.system).readJournalFor[LeveldbReadJournal]("akka.persistence.query.journal.leveldb")

    readJournal.eventsByPersistenceId("mediaLib").runForeach {
      case EventEnvelope(_, _, _, e: MediaLibEventSourcing.Event) =>
        indexActor.tell(e, ActorRef.noSender)
    }

    indexActor
  }

  class LocalIndexActor(config: MediaLibConfig) extends Actor with Logging {

    var counter: Long = 0L
    var indexedAt: Long = 0L
    var state: State = State(Map.empty)
    var directories: List[Directory] = List.empty
    var sortedByFilename: List[Media]  = List.empty
    var sortedByDateAdded: List[Media] = List.empty
    var sortedByDuration: List[Media]  = List.empty

    def media: Map[String, Media] = state.media

    def updateIndex() = {

      if (indexedAt < counter) {
        logger.debug("Updating index")
        directories = {
          val dirs = media.values.foldLeft(Set.empty[String]) { case (set, e) =>
            val parent = (File(config.path) / e.fileInfo.relativePath).parent
            val dir    = s"/${config.path.relativize(parent)}"
            set + dir
          }
          dirs.toList.sorted.zipWithIndex.map { case (path, idx) => Directory(idx.toString, path) }
        }
        sortedByFilename = media.values.toList.sortBy(m => m.title.getOrElse(m.fileName()))
        sortedByDateAdded = media.values.toList.sortBy(m => m.fileInfo.creationTime)
        sortedByDuration = media.values.toList.sortBy(m => m.videoInfo.duration)
        indexedAt = counter
      }
    }

    override def receive: Receive = {

      case e: MediaLibEventSourcing.Event =>
//        logger.debug("Received event, updating state")
        state = MediaLibEventSourcing.apply(state, e)
        counter += 1

      case GetDirectories(sender) =>
        updateIndex()
        sender.tell(directories.sortBy(_.path))

      case Search(query, sender) =>
        updateIndex()

        val dir = query.directory.flatMap(t => directories.find(_.id == t))

        def filterDir(m: Media): Boolean =
          dir.map(t => m.fileInfo.relativePath.startsWith(t.path.substring(1))).getOrElse(true)
        def filterRes(m: Media): Boolean = query.minRes.map(res => m.videoInfo.resolution._2 >= res).getOrElse(true)
        def filterQuery(m: Media): Boolean =
          query.q.map(q => m.fileInfo.relativePath.toLowerCase.contains(q.toLowerCase)).getOrElse(true)

        def filterMedia(m: Media): Boolean = filterDir(m) && filterRes(m) && filterQuery(m)

        val unfiltered = query.sort match {
          case None                             => state.media.values
          case Some(Sort(FileName, false))      => sortedByFilename
          case Some(Sort(FileName, true))       => sortedByFilename.reverse
          case Some(Sort(DateAdded, false))     => sortedByDateAdded
          case Some(Sort(DateAdded, true))      => sortedByDateAdded.reverse
          case Some(Sort(VideoDuration, false)) => sortedByDuration
          case Some(Sort(VideoDuration, true))  => sortedByDuration.reverse
        }

        val result = unfiltered.filter(filterMedia)

        val offset = query.offset.getOrElse(0)
        val end    = Math.min(offset + query.n, result.size)

        val videos = if (offset > result.size) Nil else result.slice(offset, end)

        sender.tell(SearchResult(offset, result.size, videos.toList))
    }
  }
}

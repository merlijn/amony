package nl.amony.actor

import akka.actor.typed
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.persistence.query.EventEnvelope
import akka.persistence.query.PersistenceQuery
import akka.stream.Materializer
import better.files.File
import nl.amony.MediaLibConfig
import nl.amony.actor.MediaLibProtocol.{Fragment, Media, State}
import akka.actor.typed.scaladsl.adapter._
import scribe.Logging

object MediaIndex {

  sealed trait IndexQuery extends Message

  case class Playlist(id: String, title: String)
  case class GetPlaylists(sender: typed.ActorRef[List[Playlist]])    extends IndexQuery
  case class Search(query: Query, sender: typed.ActorRef[SearchResult]) extends IndexQuery
  case class SearchFragments(size: Int, offset: Int, tag: String, sender: typed.ActorRef[Seq[Fragment]]) extends IndexQuery
  case class GetTags(sender: typed.ActorRef[Set[String]]) extends IndexQuery

  sealed trait SortField
  case object FileName      extends SortField
  case object DateAdded     extends SortField
  case object Duration      extends SortField
//  case object Shuffle       extends SortField

  case class Sort(field: SortField, reverse: Boolean)
  case class Query(
      q: Option[String],
      offset: Option[Int],
      n: Int,
      tags: Set[String],
      playlist: Option[String],
      minRes: Option[Int],
      sort: Option[Sort]
  )
  case class SearchResult(offset: Int, total: Int, items: Seq[Media], tags: Map[String, Int])

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
    var playlists: List[Playlist] = List.empty
    var sortedByFilename: List[Media] = List.empty
    var sortedByDateAdded: List[Media] = List.empty
    var sortedByDuration: List[Media] = List.empty
    var tags: Set[String] = Set.empty

    def media: Map[String, Media] = state.media

    def updateIndex() = {

      if (indexedAt < counter) {
        logger.debug("Updating index")
        playlists = {
          val dirs = media.values.foldLeft(Set.empty[String]) { case (set, e) =>
            val parent = (File(config.mediaPath) / e.fileInfo.relativePath).parent
            val dir    = s"/${config.mediaPath.relativize(parent)}"
            set + dir
          }
          dirs.toList.sorted.zipWithIndex.map { case (path, idx) => Playlist(idx.toString, path) }
        }
        sortedByFilename  = media.values.toList.sortBy(m => m.title.getOrElse(m.fileName()))
        sortedByDateAdded = media.values.toList.sortBy(m => m.fileInfo.creationTime)
        sortedByDuration  = media.values.toList.sortBy(m => m.videoInfo.duration)
        tags              = media.values.flatMap(_.tags).toSet
        indexedAt         = counter
      }
    }

    override def receive: Receive = {

      case e: MediaLibEventSourcing.Event =>
        state = MediaLibEventSourcing.apply(state, e)
        counter += 1

      case GetPlaylists(sender) =>
        updateIndex()
        sender.tell(playlists.sortBy(_.title))

      case GetTags(sender) =>
        updateIndex()
        sender.tell(tags)

      case SearchFragments(size, offset, tag, sender) =>
        updateIndex()
        val result = state.media.values.flatMap(_.fragments).filter {
          f => f.tags.contains(tag)
        }.drop(offset).take(size)

        sender.tell(result.toSeq)

      case Search(query, sender) =>
        updateIndex()

        val dir = query.playlist.flatMap(t => playlists.find(_.id == t))

        def filterDir(m: Media): Boolean =
          dir.map(t => m.fileInfo.relativePath.startsWith(t.title.substring(1))).getOrElse(true)
        def filterRes(m: Media): Boolean = query.minRes.map(res => m.videoInfo.resolution._2 >= res).getOrElse(true)
        def filterQuery(m: Media): Boolean =
          query.q.map(q => m.fileInfo.relativePath.toLowerCase.contains(q.toLowerCase)).getOrElse(true)

        def filterTag(m: Media): Boolean = 
          query.tags.forall(tag => m.tags.contains(tag))

        def filterMedia(m: Media): Boolean = filterDir(m) && filterRes(m) && filterQuery(m) && filterTag(m)

        val unfiltered = query.sort match {
          case None                             => state.media.values
          case Some(Sort(FileName, false))      => sortedByFilename
          case Some(Sort(FileName, true))       => sortedByFilename.reverse
          case Some(Sort(DateAdded, false))     => sortedByDateAdded
          case Some(Sort(DateAdded, true))      => sortedByDateAdded.reverse
          case Some(Sort(Duration, false))      => sortedByDuration
          case Some(Sort(Duration, true))       => sortedByDuration.reverse
        }

        val result = unfiltered.filter(filterMedia)

        val offset = query.offset.getOrElse(0)
        val end    = Math.min(offset + query.n, result.size)

        val videos = if (offset > result.size) Nil else result.slice(offset, end)

        sender.tell(SearchResult(offset, result.size, videos.toList, Map.empty))
    }
  }
}

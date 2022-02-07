package nl.amony.actor.index

import akka.actor.typed.scaladsl.ActorContext
import akka.actor.{Actor, ActorRef, Props, typed}
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.persistence.query.{EventEnvelope, PersistenceQuery}
import akka.stream.Materializer
import better.files.File
import nl.amony.MediaLibConfig
import nl.amony.actor.media.MediaLibProtocol.{Fragment, Media, State}
import scribe.Logging
import QueryProtocol._
import nl.amony.actor.media.MediaLibEventSourcing

object LocalIndex {

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
    var sortedBySize: List[Media] = List.empty
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
        sortedByDateAdded = media.values.toList.sortBy(_.fileInfo.creationTime)
        sortedByDuration  = media.values.toList.sortBy(_.videoInfo.duration)
        sortedBySize      = media.values.toList.sortBy(_.fileInfo.size)
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

      case SearchFragments(size, offset, maybeTag, sender) =>

        updateIndex()

        val results = state.media.values
          .flatMap(m => m.fragments.map(f => m.id -> f))
          .filter {
            case (_, f) => maybeTag.map(tag => f.tags.contains(tag)).getOrElse(true)
          }.drop(offset).take(size)

        sender.tell(results.toSeq)

      case Search(query, sender) =>
        updateIndex()

        val dir = query.playlist.flatMap(t => playlists.find(_.id == t))

        def filterDir(m: Media): Boolean =
          dir.map(t => m.fileInfo.relativePath.startsWith(t.title.substring(1))).getOrElse(true)
        def filterRes(m: Media): Boolean = query.minRes.map(res => m.height >= res).getOrElse(true)
        def filterQuery(m: Media): Boolean =
          query.q.map(q => m.fileInfo.relativePath.toLowerCase.contains(q.toLowerCase)).getOrElse(true)
        def filterTag(m: Media): Boolean =
          query.tags.forall(tag => m.tags.contains(tag))
        def filterDuration(m: Media): Boolean =
          query.duration.map {
            case (min, max) => m.videoInfo.duration >= min && m.videoInfo.duration <= max
          }.getOrElse(true)
        def filterMedia(m: Media): Boolean = filterDir(m) && filterRes(m) && filterQuery(m) && filterTag(m) && filterDuration(m)

        val unfiltered = query.sort match {
          case None                           => state.media.values
          case Some(Sort(FileName, Asc))      => sortedByFilename
          case Some(Sort(FileName, Desc))     => sortedByFilename.reverse
          case Some(Sort(DateAdded, Asc))     => sortedByDateAdded
          case Some(Sort(DateAdded, Desc))    => sortedByDateAdded.reverse
          case Some(Sort(Duration, Asc))      => sortedByDuration
          case Some(Sort(Duration, Desc))     => sortedByDuration.reverse
          case Some(Sort(FileSize, Asc))      => sortedBySize
          case Some(Sort(FileSize, Desc))     => sortedBySize.reverse
        }

        val result = unfiltered.filter(filterMedia)

        val offset = query.offset.getOrElse(0)
        val end    = Math.min(offset + query.n, result.size)

        val videos = if (offset > result.size) Nil else result.slice(offset, end)

        sender.tell(SearchResult(offset, result.size, videos.toList, Map.empty))
    }
  }
}

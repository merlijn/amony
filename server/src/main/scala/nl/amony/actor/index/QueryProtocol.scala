package nl.amony.actor.index

import akka.actor.typed
import nl.amony.actor.media.MediaLibProtocol.{Fragment, Media}
import nl.amony.actor.Message

object QueryProtocol {

  sealed trait QueryMessage extends Message

  case class Playlist(id: String, title: String)
  case class GetPlaylists(sender: typed.ActorRef[List[Playlist]])    extends QueryMessage
  case class Search(query: Query, sender: typed.ActorRef[SearchResult]) extends QueryMessage
  case class SearchFragments(size: Int, offset: Int, tag: Option[String], sender: typed.ActorRef[Seq[(String, Fragment)]]) extends QueryMessage
  case class GetTags(sender: typed.ActorRef[Set[String]]) extends QueryMessage

  sealed trait SortField
  case object FileName      extends SortField
  case object DateAdded     extends SortField
  case object Duration      extends SortField
  case object FileSize      extends SortField
  //  case object Shuffle       extends SortField

  sealed trait SortDirection
  case object Asc extends SortDirection
  case object Desc extends SortDirection

  case class Sort(field: SortField, direction: SortDirection)
  case class Query(
    q: Option[String],
    offset: Option[Int],
    n: Int,
    tags: Set[String],
    playlist: Option[String],
    minRes: Option[Int],
    duration: Option[(Long,Long)],
    sort: Option[Sort]
  )

  case class SearchResult(offset: Int, total: Int, items: Seq[Media], tags: Map[String, Int])
}

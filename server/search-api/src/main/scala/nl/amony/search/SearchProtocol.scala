package nl.amony.search

import akka.actor.typed
import akka.actor.typed.receptionist.ServiceKey
import nl.amony.service.fragments.FragmentProtocol.Fragment
import nl.amony.service.media.MediaProtocol.Media

object SearchProtocol {

  sealed trait QueryMessage

  object QueryMessage {
    implicit val serviceKey: ServiceKey[QueryMessage] = ServiceKey[QueryMessage]("searchService")
  }

  case class Search(query: Query, sender: typed.ActorRef[SearchResult]) extends QueryMessage
  case class SearchFragments(
      size: Int,
      offset: Int,
      tag: Option[String],
      sender: typed.ActorRef[Seq[Fragment]]
  )                                                       extends QueryMessage
  case class GetTags(sender: typed.ActorRef[Set[String]]) extends QueryMessage

  sealed trait SortField
  case object Title     extends SortField
  case object DateAdded extends SortField
  case object Duration  extends SortField
  case object Size      extends SortField
  //  case object Shuffle       extends SortField

  sealed trait SortDirection
  case object Asc  extends SortDirection
  case object Desc extends SortDirection

  case class Sort(field: SortField, direction: SortDirection)
  case class Query(
      q: Option[String],
      offset: Option[Int],
      n: Int,
      tags: Set[String],
      playlist: Option[String],
      minRes: Option[Int],
      duration: Option[(Long, Long)],
      sort: Option[Sort]
  )

  case class SearchResult(offset: Long, total: Long, items: Seq[Media], tags: Map[String, Int])
}

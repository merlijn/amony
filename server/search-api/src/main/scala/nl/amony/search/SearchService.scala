package nl.amony.search

import nl.amony.search.SearchProtocol._
import nl.amony.service.fragments.FragmentProtocol.Fragment

import scala.concurrent.Future

trait SearchService  {

  // format: off
  def searchMedia(q: Option[String], offset: Option[Int], size: Int, tags: Set[String], playlist: Option[String],
                  minRes: Option[Int], duration: Option[(Long,Long)], sort: Sort): Future[SearchResult]

  def searchTags(): Future[Set[String]]

  def searchFragments(size: Int, offset: Int, tag: Option[String]): Future[Seq[Fragment]]
  // format: on
}

package nl.amony.search

import akka.actor.typed.ActorSystem
import nl.amony.lib.akka.AkkaServiceModule
import nl.amony.search.SearchProtocol._
import nl.amony.service.fragments.Protocol.Fragment

import scala.concurrent.Future

class SearchService(system: ActorSystem[Nothing]) extends AkkaServiceModule(system) {

  // format: off
  def searchMedia(q: Option[String], offset: Option[Int], size: Int, tags: Set[String], playlist: Option[String],
                  minRes: Option[Int], duration: Option[(Long,Long)], sort: Sort): Future[SearchResult] =
    ask[QueryMessage, SearchResult](ref => Search(Query(q, offset, size, tags, playlist, minRes, duration, Some(sort)), ref))

  def searchTags(): Future[Set[String]] =
    ask[QueryMessage, Set[String]](ref => GetTags(ref))

  def searchFragments(size: Int, offset: Int, tag: Option[String]): Future[Seq[Fragment]] =
    ask[QueryMessage, Seq[Fragment]](ref => SearchFragments(size, offset, tag, ref))
  // format: on
}

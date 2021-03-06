package nl.amony.search

import akka.actor.typed.ActorSystem
import nl.amony.lib.akka.AkkaServiceModule
import nl.amony.search.SearchProtocol._
import nl.amony.service.media.actor.MediaLibProtocol.Fragment

import scala.concurrent.Future

class SearchApi(system: ActorSystem[Nothing]) extends AkkaServiceModule[QueryMessage](system) {

  // format: off
  def searchMedia(q: Option[String], offset: Option[Int], size: Int, tags: Set[String], playlist: Option[String],
                  minRes: Option[Int], duration: Option[(Long,Long)], sort: Sort): Future[SearchResult] =
    askService[SearchResult](ref => Search(Query(q, offset, size, tags, playlist, minRes, duration, Some(sort)), ref))

  def searchTags(): Future[Set[String]] =
    askService[Set[String]](ref => GetTags(ref))

  def searchFragments(size: Int, offset: Int, tag: Option[String]): Future[Seq[(String, Fragment)]] =
    askService[Seq[(String, Fragment)]](ref => SearchFragments(size, offset, tag, ref))
  // format: on
}

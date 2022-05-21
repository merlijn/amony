package nl.amony.search

import akka.actor.typed.ActorSystem
import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.scaladsl.AskPattern._
import akka.util.Timeout
import nl.amony.service.media.actor.MediaLibProtocol.Fragment
import nl.amony.lib.akka.AkkaServiceModule
import nl.amony.search.SearchProtocol._

import scala.concurrent.Future

object SearchApi {

  val searchServiceKey = ServiceKey[QueryMessage]("searchService")
}

class SearchApi(val system: ActorSystem[Nothing], override implicit val askTimeout: Timeout) extends AkkaServiceModule[QueryMessage] {

  override val serviceKey = SearchApi.searchServiceKey

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

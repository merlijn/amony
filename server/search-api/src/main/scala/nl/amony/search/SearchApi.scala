package nl.amony.search

import akka.actor.typed.{ActorRef, ActorSystem}
import akka.actor.typed.receptionist.Receptionist.Find
import akka.actor.typed.receptionist.{Receptionist, ServiceKey}
import akka.actor.typed.scaladsl.AskPattern._
import akka.util.Timeout
import nl.amony.actor.media.MediaLibProtocol.Fragment
import nl.amony.search.SearchApi.searchServiceKey
import nl.amony.search.SearchProtocol._

import scala.concurrent.Future

object SearchApi {

  val searchServiceKey = ServiceKey[QueryMessage]("searchService")
}

class SearchApi(val system: ActorSystem[Nothing]) {

  implicit val scheduler = system.scheduler
  implicit val ec        = system.executionContext

  private def searchRef()(implicit timeout: Timeout): Future[ActorRef[QueryMessage]] =
    system.receptionist
      .ask[Receptionist.Listing](ref => Find(searchServiceKey, ref))(timeout, system.scheduler)
      .map(_.serviceInstances(searchServiceKey).head)

  // format: off
  def searchMedia(q: Option[String], offset: Option[Int], size: Int, tags: Set[String], playlist: Option[String],
                  minRes: Option[Int], duration: Option[(Long,Long)], sort: Sort)(implicit timeout: Timeout): Future[SearchResult] =
    searchRef().flatMap(_.ask[SearchResult](ref => Search(Query(q, offset, size, tags, playlist, minRes, duration, Some(sort)), ref)))

  def searchTags()(implicit timeout: Timeout): Future[Set[String]] =
    searchRef().flatMap(_.ask[Set[String]](ref => GetTags(ref)))

  def searchFragments(size: Int, offset: Int, tag: Option[String])(implicit timeout: Timeout): Future[Seq[(String, Fragment)]] =
    searchRef().flatMap(_.ask[Seq[(String, Fragment)]](ref => SearchFragments(size, offset, tag, ref)))
  // format: on
}

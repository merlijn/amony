package nl.amony.api

import akka.actor.typed.ActorSystem
import akka.util.Timeout
import nl.amony.actor.Message
import nl.amony.actor.index.QueryProtocol.{GetTags, Query, QueryMessage, Search, SearchFragments, SearchResult, Sort}
import nl.amony.actor.media.MediaLibProtocol.{Fragment, GetAll, GetById, Media}
import akka.actor.typed.scaladsl.AskPattern._

import scala.concurrent.Future

class SearchApi(val system: ActorSystem[QueryMessage]) {

  implicit val scheduler            = system.scheduler

  // format: off
  def searchMedia(q: Option[String], offset: Option[Int], size: Int, tags: Set[String], playlist: Option[String],
                  minRes: Option[Int], duration: Option[(Long,Long)], sort: Sort)(implicit timeout: Timeout): Future[SearchResult] =
    system.ask[SearchResult](ref => Search(Query(q, offset, size, tags, playlist, minRes, duration, Some(sort)), ref))

  def searchTags()(implicit timeout: Timeout): Future[Set[String]] =
    system.ask[Set[String]](ref => GetTags(ref))

  def searchFragments(size: Int, offset: Int, tag: Option[String])(implicit timeout: Timeout): Future[Seq[(String, Fragment)]] =
    system.ask[Seq[(String, Fragment)]](ref => SearchFragments(size, offset, tag, ref))
  // format: on
}

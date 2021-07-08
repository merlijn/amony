package com.github.merlijn.kagera.actor

import akka.actor.typed.ActorSystem
import akka.persistence.query.PersistenceQuery
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.util.Timeout
import better.files.File
import com.github.merlijn.kagera.actor.MediaLibActor.{Command, GetById, GetCollections, Query, Search, SetThumbnail}
import com.github.merlijn.kagera.http.Model.{Collection, SearchResult, Video}
import com.github.merlijn.kagera.lib.MediaLibConfig

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

class MediaLibApi(config: MediaLibConfig, system: ActorSystem[Command]) {

  import akka.actor.typed.scaladsl.AskPattern._
  implicit val scheduler = system.scheduler
  implicit val timeout: Timeout = 3.seconds

  val queries = PersistenceQuery(system).readJournalFor[LeveldbReadJournal](LeveldbReadJournal.Identifier)

  def getById(id: String): Option[Video] = {
    val result = system.ask[Option[Video]](ref => GetById(id, ref))
    Await.result(result, timeout.duration)
  }

  def search(q: Option[String], page: Int, size: Int, c: Option[Int]): Future[SearchResult] =
    system.ask[SearchResult](ref => Search(Query(q, page, size, c), ref))

  def getCollections(): Future[List[Collection]] =
    system.ask[List[Collection]](ref => GetCollections(ref))

  def setThumbnailAt(id: String, timestamp: Long): Future[Option[Video]] =
    system.ask[Option[Video]](ref => SetThumbnail(id, timestamp, ref))

  def getThumbnailPathForMedia(id: String): String =
    s"${config.indexPath}/$id"

  def getFilePathForMedia(vid: Video): String =
    (File(config.libraryPath) / vid.fileName).path.toAbsolutePath.toString
}

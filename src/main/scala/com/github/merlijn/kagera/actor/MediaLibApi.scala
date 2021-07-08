package com.github.merlijn.kagera.actor

import akka.actor.typed.ActorSystem
import akka.persistence.query.PersistenceQuery
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.util.Timeout
import better.files.File
import com.github.merlijn.kagera.actor.MediaLibActor._
import com.github.merlijn.kagera.http.Model.{Collection, SearchResult, Video}
import com.github.merlijn.kagera.lib.MediaLibConfig

import scala.concurrent.{Await, Future}

class MediaLibApi(config: MediaLibConfig, system: ActorSystem[Command]) {

  import akka.actor.typed.scaladsl.AskPattern._
  implicit val scheduler = system.scheduler

  val queries = PersistenceQuery(system).readJournalFor[LeveldbReadJournal](LeveldbReadJournal.Identifier)

  def getById(id: String)(implicit timeout: Timeout): Option[Video] = {
    val result = system.ask[Option[Video]](ref => GetById(id, ref))
    Await.result(result, timeout.duration)
  }

  def getAll()(implicit timeout: Timeout): Future[List[Video]] =
    system.ask[List[Video]](ref => GetAll(ref))

  def search(q: Option[String], page: Int, size: Int, c: Option[Int])(implicit timeout: Timeout): Future[SearchResult] =
    system.ask[SearchResult](ref => Search(Query(q, page, size, c), ref))

  def getCollections()(implicit timeout: Timeout): Future[List[Collection]] =
    system.ask[List[Collection]](ref => GetCollections(ref))

  def setThumbnailAt(id: String, timestamp: Long)(implicit timeout: Timeout): Future[Option[Video]] =
    system.ask[Option[Video]](ref => SetThumbnail(id, timestamp, ref))

  def getThumbnailPathForMedia(id: String): String =
    s"${config.indexPath}/$id"

  def getFilePathForMedia(vid: Video): String =
    (File(config.libraryPath) / vid.fileName).path.toAbsolutePath.toString
}

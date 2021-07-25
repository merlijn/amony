package io.amony.actor

import akka.actor.typed.ActorSystem
import akka.persistence.query.PersistenceQuery
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.serialization.jackson.JacksonObjectMapperProvider
import akka.util.Timeout
import better.files.File
import com.fasterxml.jackson.core.JsonEncoding
import com.fasterxml.jackson.core.`type`.TypeReference
import io.amony.actor.MediaLibActor._
import io.amony.lib.FileUtil.stripExtension
import io.amony.lib.{FileUtil, MediaLibConfig, MediaLibScanner}
import scribe.Logging

import scala.concurrent.{Await, Future}

class MediaLibApi(config: MediaLibConfig, system: ActorSystem[Command]) extends Logging {

  import akka.actor.typed.scaladsl.AskPattern._
  implicit val scheduler = system.scheduler
  implicit val ec        = system.executionContext

  val queries = PersistenceQuery(system).readJournalFor[LeveldbReadJournal](LeveldbReadJournal.Identifier)

  def getById(id: String)(implicit timeout: Timeout): Option[Media] = {
    val result = system.ask[Option[Media]](ref => GetById(id, ref))
    Await.result(result, timeout.duration)
  }

  def getAll()(implicit timeout: Timeout): Future[List[Media]] =
    system.ask[List[Media]](ref => GetAll(ref))

  def search(q: Option[String], offset: Option[Int], size: Int, c: Option[String])(implicit
      timeout: Timeout
  ): Future[SearchResult] =
    system.ask[SearchResult](ref => Search(Query(q, offset, size, c), ref))

  def getCollections()(implicit timeout: Timeout): Future[List[Collection]] =
    system.ask[List[Collection]](ref => GetCollections(ref))

  def setThumbnailAt(id: String, timestamp: Long)(implicit timeout: Timeout): Future[Option[Media]] =
    system.ask[Option[Media]](ref => SetThumbnail(id, timestamp, ref))

  def getThumbnailPathForMedia(id: String): String =
    s"${config.indexPath}/thumbnails/$id"

  def getFilePathForMedia(vid: Media): String =
    (File(config.libraryPath) / vid.uri).path.toAbsolutePath.toString

  def regenerateThumbnails()(implicit timeout: Timeout) = {

    getAll().foreach { medias =>
      medias.foreach { m =>
        logger.info(s"re-generating thumbnail for '${m.fileName()}''")
        val videoPath = config.libraryPath.resolve(m.uri)

        MediaLibScanner.generateThumbnail(videoPath, config.indexPath, m.id, m.thumbnail.timestamp)
      }
    }
  }

  val objectMapper = JacksonObjectMapperProvider.get(system).getOrCreate("media-export", None)

  def exportLibrary()(implicit timeout: Timeout): Unit = {

    val file = (File(config.indexPath) / "export.json").path.toFile

    getAll().foreach { medias =>
      objectMapper.createGenerator(file, JsonEncoding.UTF8).writeObject(medias)
    }
  }

  def importFromFile(): Unit = {

    val file = (File(config.indexPath) / "export.json").path.toFile

    val typeRef: TypeReference[List[Media]] = new TypeReference[List[Media]]() {}

    val medias = objectMapper.createParser(file).readValueAs(typeRef).asInstanceOf[List[Media]]

    medias.take(10).foreach { m =>
      logger.info(s"read '${m.fileName()}'")
    }
  }

  def resetTitles()(implicit timeout: Timeout): Unit = {

    getAll().foreach {
      _.foreach { m =>
        logger.info(s"Blanking title for: ${m.uri}")
        system.tell(UpsertMedia(m.copy(title = None)))
      }
    }
  }
}

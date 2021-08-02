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

  def getTags()(implicit timeout: Timeout): Future[List[Collection]] =
    system.ask[List[Collection]](ref => GetCollections(ref))

  def upsertMedia(media: Media)(implicit timeout: Timeout): Future[Media] = {
    system.ask[Boolean](ref => UpsertMedia(media, ref)).map(_ => media)
  }

  def deleteMedia(id: String)(implicit timeout: Timeout): Future[Unit] = {
    system.ask[Boolean](ref => RemoveMedia(id, ref)).map(_ => ())
  }

  def addFragment(id: String, from: Long, to: Long)(implicit timeout: Timeout): Future[Option[Media]] =
    system.ask[Option[Media]](ref => AddFragment(id, from, to, ref))

  def updateFragment(id: String, idx: Int, from: Long, to: Long)(implicit timeout: Timeout): Future[Option[Media]] =
    system.ask[Option[Media]](ref => UpdateFragment(id, idx, from, to, ref))

  def deleteFragment(id: String, idx: Int)(implicit timeout: Timeout): Future[Option[Media]] =
    system.ask[Option[Media]](ref => DeleteFragment(id, idx, ref))

  def getThumbnailPathForMedia(id: String): String =
    s"${config.indexPath}/thumbnails/$id"

  def getFilePathForMedia(vid: Media): String =
    (File(config.libraryPath) / vid.uri).path.toAbsolutePath.toString

  def regeneratePreviewFor(m: Media): Unit = {
    val videoPath = config.libraryPath.resolve(m.uri)
    m.fragments.foreach { f =>
      MediaLibScanner.generateVideoFragment(videoPath, config.indexPath, m.id, f.fromTimestamp, f.toTimestamp)
    }
  }

  def regeneratePreviewsFor(id: String)(implicit timeout: Timeout) = {
    getById(id).foreach { m => regeneratePreviewFor(m) }
  }

  def regeneratePreviews()(implicit timeout: Timeout) = {

    getAll().foreach { medias =>
      medias.foreach { m =>
        logger.info(s"re-generating thumbnail for '${m.fileName()}'")
        regeneratePreviewFor(m)
      }
    }
  }

  def verifyHashes()(implicit timeout: Timeout) = {

    getAll().foreach { medias =>
      logger.info("Verifying all file hashes ...")

      medias.foreach { m =>
        val hash = FileUtil.fakeHash(File(config.libraryPath) / m.uri)

        if (hash != m.hash)
          logger.info(s"Found different hash for: ${m.uri}")
      }

      logger.info("Done ...")
    }
  }

  val objectMapper = JacksonObjectMapperProvider.get(system).getOrCreate("media-export", None)

  def exportLibrary()(implicit timeout: Timeout): Unit = {

    val file = (File(config.indexPath) / "export.json").path.toFile

    getAll().foreach { medias =>
      objectMapper.createGenerator(file, JsonEncoding.UTF8).writeObject(medias)
    }
  }
}

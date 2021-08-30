package nl.amony.lib

import akka.actor.typed.ActorSystem
import akka.persistence.query.PersistenceQuery
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.serialization.jackson.JacksonObjectMapperProvider
import akka.util.Timeout
import better.files.File
import com.fasterxml.jackson.core.JsonEncoding
import nl.amony.MediaLibConfig
import nl.amony.actor.MediaLibActor
import nl.amony.actor.MediaLibActor._
import nl.amony.lib.MediaLibScanner.{logger, scanVideosInDirectory}
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Consumer
import scribe.Logging

import scala.concurrent.{Await, Future}

class MediaLibApi(val config: MediaLibConfig, system: ActorSystem[Command]) extends Logging {

  import akka.actor.typed.scaladsl.AskPattern._
  implicit val scheduler = system.scheduler
  implicit val ec        = system.executionContext

  val queries = PersistenceQuery(system).readJournalFor[LeveldbReadJournal](LeveldbReadJournal.Identifier)

  object query {

    def getById(id: String)(implicit timeout: Timeout): Future[Option[Media]] =
      system.ask[Option[Media]](ref => GetById(id, ref))

    def getAll()(implicit timeout: Timeout): Future[List[Media]] =
      system.ask[List[Media]](ref => GetAll(ref))

    def search(q: Option[String], offset: Option[Int], size: Int, c: Option[String], minRes: Option[Int])(implicit
        timeout: Timeout
    ): Future[SearchResult] =
      system.ask[SearchResult](ref => Search(Query(q, offset, size, c, minRes), ref))

    def getTags()(implicit timeout: Timeout): Future[List[Collection]] =
      system.ask[List[Collection]](ref => GetTags(ref))

    def getThumbnailPathForMedia(id: String): String =
      s"${config.indexPath}/thumbnails/$id"

    def getFilePathForMedia(vid: Media): String =
      (File(config.libraryPath) / vid.uri).path.toAbsolutePath.toString
  }

  object modify {

    def upsertMedia(media: Media)(implicit timeout: Timeout): Future[Media] =
      system.ask[Boolean](ref => UpsertMedia(media, ref)).map(_ => media)

    def deleteMedia(id: String)(implicit timeout: Timeout): Future[Boolean] =
      system.ask[Boolean](ref => RemoveMedia(id, ref))

    def addFragment(id: String, from: Long, to: Long)(implicit timeout: Timeout): Future[Either[ErrorResponse, Media]] =
      system.ask[Either[ErrorResponse, Media]](ref => AddFragment(id, from, to, ref))

    def updateFragmentRange(id: String, idx: Int, from: Long, to: Long)(implicit
        timeout: Timeout
    ): Future[Either[ErrorResponse, Media]] =
      system.ask[Either[ErrorResponse, Media]](ref => UpdateFragmentRange(id, idx, from, to, ref))

    def updateFragmentTags(id: String, idx: Int, tags: List[String])(implicit
        timeout: Timeout
    ): Future[Either[ErrorResponse, Media]] =
      system.ask[Either[ErrorResponse, Media]](ref => UpdateFragmentTags(id, idx, tags, ref))

    def deleteFragment(id: String, idx: Int)(implicit timeout: Timeout): Future[Either[ErrorResponse, Media]] =
      system.ask[Either[ErrorResponse, Media]](ref => DeleteFragment(id, idx, ref))
  }

  object admin {

    def scanLibrary()(implicit timeout: Timeout): Unit = {

      implicit val s = Scheduler.global

      query
        .getAll()
        .foreach { loadedFromStore =>
          val (deleted, newAndMoved) = MediaLibScanner.scanVideosInDirectory(config, loadedFromStore)
          val upsert                 = Consumer.foreachTask[Media](m => Task { modify.upsertMedia(m) })
          val delete = Consumer.foreachTask[Media](m =>
            Task {
              logger.info(s"Detected deleted file: ${m.uri}")
              modify.deleteMedia(m.id)
            }
          )

          deleted.consumeWith(delete).runSyncUnsafe()
          newAndMoved.consumeWith(upsert).runSyncUnsafe()

        }(system.executionContext)
    }

    def regeneratePreviewFor(m: Media): Unit = {
      val videoPath = config.libraryPath.resolve(m.uri)
      m.fragments.foreach { f =>
        MediaLibScanner.createVideoFragment(videoPath, config.indexPath, m.id, f.fromTimestamp, f.toTimestamp)
      }
    }

    def regeneratePreviews()(implicit timeout: Timeout): Unit = {

      query.getAll().foreach { medias =>
        medias.foreach { m =>
          logger.info(s"re-generating thumbnail for '${m.fileName()}'")
          regeneratePreviewFor(m)
        }
      }
    }

    def verifyHashes()(implicit timeout: Timeout): Unit = {

      query.getAll().foreach { medias =>
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

      query.getAll().foreach { medias =>
        objectMapper.createGenerator(file, JsonEncoding.UTF8).writeObject(medias)
      }
    }

    def convertNonStreamableVideos(): Unit = {
      MediaLibScanner.convertNonStreamableVideos(config, MediaLibApi.this)
    }
  }
}

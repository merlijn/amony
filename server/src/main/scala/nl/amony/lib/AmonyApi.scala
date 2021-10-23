package nl.amony.lib

import akka.actor.typed.ActorSystem
import akka.persistence.query.PersistenceQuery
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.serialization.jackson.JacksonObjectMapperProvider
import akka.util.Timeout
import better.files.File
import better.files.File.apply
import com.fasterxml.jackson.core.JsonEncoding
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Consumer
import nl.amony.MediaLibConfig
import nl.amony.actor.MediaIndex._
import nl.amony.actor.MediaLibProtocol._
import scribe.Logging
import nl.amony.actor.Message

import java.io.InputStream
import java.nio.file.Path
import scala.concurrent.Future

class AmonyApi(val config: MediaLibConfig, system: ActorSystem[Message]) extends Logging {

  import akka.actor.typed.scaladsl.AskPattern._
  implicit val scheduler = system.scheduler
  implicit val ec        = system.executionContext

  val queries = PersistenceQuery(system).readJournalFor[LeveldbReadJournal](LeveldbReadJournal.Identifier)

  object query {

    def getById(id: String)(implicit timeout: Timeout): Future[Option[Media]] =
      system.ask[Option[Media]](ref => GetById(id, ref))

    def getAll()(implicit timeout: Timeout): Future[List[Media]] =
      system.ask[List[Media]](ref => GetAll(ref))

    def search(q: Option[String], offset: Option[Int], size: Int, tag: Option[String], minRes: Option[Int], sort: Sort)(
        implicit timeout: Timeout
    ): Future[SearchResult] =
      system.ask[SearchResult](ref => Search(Query(q, offset, size, tag, minRes, Some(sort)), ref))

    def getDirectories()(implicit timeout: Timeout): Future[List[Directory]] =
      system.ask[List[Directory]](ref => GetDirectories(ref))
  }

  object resources {

    def resourcePath(): Path = config.indexPath.resolve("resources")

    def getVideoFragment(id: String): Path = resourcePath().resolve(id)

    def getThumbnail(id: String, timestamp: Option[Long])(implicit timeout: Timeout): Future[Option[InputStream]] = {

      query.getById(id).map(_.map { media =>

        timestamp match {
          case None         =>
            File(resourcePath().resolve(s"${media.id}-${media.fragments.head.fromTimestamp}.webp")).newFileInputStream
          case Some(millis) =>
            FFMpeg.streamThumbnail(config.mediaPath.resolve(media.fileInfo.relativePath).toAbsolutePath, millis, 320)
        }
      })
    }

    def getFilePathForMedia(vid: Media): String =
      (File(config.mediaPath) / vid.fileInfo.relativePath).path.toAbsolutePath.toString
  }

  object modify {

    def upsertMedia(media: Media)(implicit timeout: Timeout): Future[Media] =
      system.ask[Boolean](ref => UpsertMedia(media, ref)).map(_ => media)

    def deleteMedia(id: String)(implicit timeout: Timeout): Future[Boolean] =
      system.ask[Boolean](ref => RemoveMedia(id, ref))

    def updateMetaData(id: String, title: Option[String], comment: Option[String], tags: List[String])(implicit
        timeout: Timeout
    ): Future[Either[ErrorResponse, Media]] =
      system.ask[Either[ErrorResponse, Media]](ref => UpdateMetaData(id, title, comment, tags.toSet, ref))

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
              logger.info(s"Detected deleted file: ${m.fileInfo.relativePath}")
              modify.deleteMedia(m.id)
            }
          )

          deleted.consumeWith(delete).runSyncUnsafe()
          newAndMoved.consumeWith(upsert).runSyncUnsafe()

        }(system.executionContext)
    }

    def regeneratePreviewFor(m: Media): Unit = {
      val videoPath = config.mediaPath.resolve(m.fileInfo.relativePath)

      m.fragments.foreach { f =>
        logger.info(s"Generating preview(s) for: ${m.fileInfo.relativePath}")
        MediaLibScanner.createVideoFragment(videoPath, config.indexPath, m.id, f.fromTimestamp, f.toTimestamp, config.previews)
      }
    }

    def generateThumbnailPreviews()(implicit timeout: Timeout): Unit = {
      query.getAll().foreach { medias =>
        medias.foreach { m =>
          logger.info(s"generating thumbnail previews for '${m.fileName()}'")
          FFMpeg.generatePreviewSprite(
            m.resolvePath(config.mediaPath).toAbsolutePath,
            outputDir = config.indexPath.resolve("resources"),
            outputBaseName = Some(s"${m.id}-timeline")
          )
        }
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
          val hash = config.hashingAlgorithm.generateHash(config.mediaPath.resolve(m.fileInfo.relativePath))

          if (hash != m.fileInfo.hash)
            logger.info(s"Found different hash for: ${m.fileInfo.relativePath}")
        }

        logger.info("Done ...")
      }
    }

    val objectMapper = JacksonObjectMapperProvider.get(system).getOrCreate("media-export", None)

    def exportLibrary()(implicit timeout: Timeout): Unit = {

      val file = config.indexPath.resolve("export.json").toFile

      query.getAll().foreach { medias =>
        objectMapper.createGenerator(file, JsonEncoding.UTF8).writeObject(medias)
      }
    }

    def convertNonStreamableVideos(): Unit = {
      MediaLibScanner.convertNonStreamableVideos(config, AmonyApi.this)
    }
  }
}

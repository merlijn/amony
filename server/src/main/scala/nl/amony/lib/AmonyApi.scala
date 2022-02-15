package nl.amony.lib

import akka.actor.typed.ActorSystem
import akka.serialization.jackson.JacksonObjectMapperProvider
import akka.util.Timeout
import better.files.File
import com.fasterxml.jackson.core.JsonEncoding
import monix.eval.Task
import monix.reactive.Consumer
import nl.amony.AmonyConfig
import nl.amony.actor.media.MediaLibProtocol._
import nl.amony.actor.Message
import nl.amony.actor.index.QueryProtocol._
import nl.amony.actor.user.UserProtocol.Authenticate
import nl.amony.lib.ffmpeg.FFMpeg
import nl.amony.tasks.{ConvertNonStreamableVideos, MediaScanner, ResourceTasks}
import scribe.Logging

import java.io.InputStream
import java.nio.file.Path
import scala.concurrent.{ExecutionContext, Future}

class AmonyApi(val config: AmonyConfig, scanner: MediaScanner, system: ActorSystem[Message]) extends Logging {

  import akka.actor.typed.scaladsl.AskPattern._
  implicit val scheduler            = system.scheduler
  implicit val ec: ExecutionContext = system.executionContext
  implicit val mScheduler           = monix.execution.Scheduler.Implicits.global

  // format: off
  object query {

    def getById(id: String)(implicit timeout: Timeout): Future[Option[Media]] =
      system.ask[Option[Media]](ref => GetById(id, ref))

    def getAll()(implicit timeout: Timeout): Future[List[Media]] =
      system.ask[List[Media]](ref => GetAll(ref))

    def search(q: Option[String], offset: Option[Int], size: Int, tags: Set[String], playlist: Option[String],
               minRes: Option[Int], duration: Option[(Long,Long)], sort: Sort)(implicit timeout: Timeout): Future[SearchResult] =
      system.ask[SearchResult](ref => Search(Query(q, offset, size, tags, playlist, minRes, duration, Some(sort)), ref))

    def getPlaylists()(implicit timeout: Timeout): Future[List[Playlist]] =
      system.ask[List[Playlist]](ref => GetPlaylists(ref))

    def getTags()(implicit timeout: Timeout): Future[Set[String]] =
      system.ask[Set[String]](ref => GetTags(ref))

    def searchFragments(size: Int, offset: Int, tag: Option[String])(implicit timeout: Timeout): Future[Seq[(String, Fragment)]] =
      system.ask[Seq[(String, Fragment)]](ref => SearchFragments(size, offset, tag, ref))
  }

  object modify {

    def addMediaFromLocalFile(path: Path)(implicit timeout: Timeout): Future[Media] = {

      scanner
        .scanMedia(path.toAbsolutePath, None, config.media)
        .runToFuture
        .flatMap { media =>
          // TODO this logic should move to the actor
          query.getById(media.id).flatMap {
            case None    => modify.upsertMedia(media)
            case Some(_) => Future.failed(new IllegalStateException("Media with hash already exists"))
          }
        }
    }

    def upsertMedia(media: Media)(implicit timeout: Timeout): Future[Media] =
      system.ask[Boolean](ref => UpsertMedia(media, ref)).map(_ => media)

    def deleteMedia(id: String, deleteResource: Boolean)(implicit timeout: Timeout): Future[Boolean] =
      system.ask[Boolean](ref => RemoveMedia(id, deleteResource, ref))

    def updateMetaData(id: String, title: Option[String], comment: Option[String], tags: List[String])(implicit timeout: Timeout): Future[Either[ErrorResponse, Media]] =
      system.ask[Either[ErrorResponse, Media]](ref => UpdateMetaData(id, title, comment, tags.toSet, ref))

    def addFragment(id: String, from: Long, to: Long)(implicit timeout: Timeout): Future[Either[ErrorResponse, Media]] =
      system.ask[Either[ErrorResponse, Media]](ref => AddFragment(id, from, to, ref))

    def updateFragmentRange(id: String, idx: Int, from: Long, to: Long)(implicit timeout: Timeout): Future[Either[ErrorResponse, Media]] =
      system.ask[Either[ErrorResponse, Media]](ref => UpdateFragmentRange(id, idx, from, to, ref))

    def updateFragmentTags(id: String, idx: Int, tags: List[String])(implicit timeout: Timeout): Future[Either[ErrorResponse, Media]] =
      system.ask[Either[ErrorResponse, Media]](ref => UpdateFragmentTags(id, idx, tags, ref))

    def deleteFragment(id: String, idx: Int)(implicit timeout: Timeout): Future[Either[ErrorResponse, Media]] =
      system.ask[Either[ErrorResponse, Media]](ref => DeleteFragment(id, idx, ref))
  }
  // format: on

  object resources {

    def resourcePath(): Path = config.media.resourcePath

    def getVideo(id: String)(implicit timeout: Timeout): Future[Option[Path]] = {

      query
        .getById(id)
        .map(_.map { m =>
          config.media.mediaPath.resolve(m.fileInfo.relativePath)
        })
    }

    def getVideoFragment(id: String, quality: Int, start: Long, end: Long): Path =
      resourcePath().resolve(s"$id-$start-${end}_${quality}p.mp4")

    def getThumbnail(id: String, quality: Int, timestamp: Option[Long])(implicit
        timeout: Timeout
    ): Future[Option[InputStream]] = {
      query
        .getById(id)
        .map(_.map { media =>
          timestamp match {
            case None =>
              File(
                resourcePath().resolve(s"${media.id}-${media.fragments.head.fromTimestamp}_${quality}p.webp")
              ).newFileInputStream
            case Some(millis) =>
              FFMpeg.streamThumbnail(config.media.mediaPath.resolve(media.fileInfo.relativePath).toAbsolutePath, millis, 320)
          }
        })
    }

    def getFilePathForMedia(vid: Media): Path = config.media.mediaPath.resolve(vid.fileInfo.relativePath)
  }

  object users {

    def login(username: String, password: String)(implicit timeout: Timeout): Future[Boolean] = {
      system.ask[Boolean](ref => Authenticate(username, password, ref))
    }
  }

  object admin {

    def scanLibrary()(implicit timeout: Timeout): Unit = {

      query
        .getAll()
        .foreach { loadedFromStore =>
          val (deleted, newAndMoved) = scanner.scanMediaInDirectory(config.media, loadedFromStore)

          val upsert                 = Consumer.foreachTask[Media](m => Task {
            modify.upsertMedia(m)
          })

          val delete = Consumer.foreachTask[Media](m =>
            Task {
              logger.info(s"Detected deleted file: ${m.fileInfo.relativePath}")
              modify.deleteMedia(m.id, deleteResource = false)
            }
          )

          deleted.consumeWith(delete).runSyncUnsafe()
          newAndMoved.consumeWith(upsert).runSyncUnsafe()

        }(system.executionContext)
    }

    def generateThumbnailPreviews()(implicit timeout: Timeout): Unit = {
      query.getAll().foreach { medias =>
        medias.foreach { m =>
          logger.info(s"generating thumbnail previews for '${m.fileName()}'")
          FFMpeg.generatePreviewSprite(
            inputFile      = m.resolvePath(config.media.mediaPath).toAbsolutePath,
            outputDir      = config.media.resourcePath,
            outputBaseName = Some(s"${m.id}-timeline")
          )
        }
      }
    }

    def regeneratePreviewForMedia(media: Media): Unit = {
      logger.info(s"re-generating previews for '${media.fileInfo.relativePath}'")
      ResourceTasks.createFragments(
        config = config.media,
        media  = media,
        overwrite  = true
      )
    }

    def regenerateAllPreviews()(implicit timeout: Timeout): Unit =
      query.getAll().foreach { medias => medias.foreach(regeneratePreviewForMedia) }

    def verifyHashes()(implicit timeout: Timeout): Unit = {

      query.getAll().foreach { medias =>
        logger.info("Verifying all file hashes...")

        medias.foreach { m =>
          val hash = config.media.hashingAlgorithm.generateHash(config.media.mediaPath.resolve(m.fileInfo.relativePath))

          if (hash != m.fileInfo.hash)
            logger.warn(s"hash not equal: ${hash} != ${m.fileInfo.hash}")
        }

        logger.info("Done ...")
      }
    }

    def updateHashes()(implicit timeout: Timeout): Unit = {

      query.getAll().foreach { medias =>
        logger.info("Updating hashes ...")

        medias.foreach { m =>
          val hash = config.media.hashingAlgorithm.generateHash(config.media.mediaPath.resolve(m.fileInfo.relativePath))

          if (hash != m.fileInfo.hash) {

            logger.info(s"Updating hash from '${m.fileInfo.hash}' to '$hash' for '${m.fileName()}''")
            modify.upsertMedia(m.copy(id = hash, fileInfo = m.fileInfo.copy(hash = hash)))
            modify.deleteMedia(id = m.id, deleteResource = false)
          }
        }

        logger.info("Done ...")
      }
    }

    val objectMapper = JacksonObjectMapperProvider.get(system).getOrCreate("media-export", None)

    def exportLibrary()(implicit timeout: Timeout): Unit = {

      val file = config.media.indexPath.resolve("export.json").toFile

      query.getAll().foreach { medias =>
        objectMapper.createGenerator(file, JsonEncoding.UTF8).writeObject(medias)
      }
    }

    def convertNonStreamableVideos(): Unit = ConvertNonStreamableVideos.convertNonStreamableVideos(config, AmonyApi.this)
  }
}

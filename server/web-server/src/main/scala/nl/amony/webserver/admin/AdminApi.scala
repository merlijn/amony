package nl.amony.webserver.admin

import akka.actor.typed.ActorSystem
import akka.serialization.jackson.JacksonObjectMapperProvider
import akka.util.Timeout
import com.fasterxml.jackson.core.JsonEncoding
import monix.eval.Task
import monix.reactive.Consumer
import nl.amony.lib.ffmpeg.FFMpeg
import nl.amony.service.media.MediaApi
import nl.amony.service.media.actor.MediaLibProtocol.Media
import nl.amony.service.resources.ResourceApi
import nl.amony.service.resources.local.LocalMediaScanner
import nl.amony.webserver.AmonyConfig
import scribe.Logging

import java.io.ByteArrayOutputStream
import scala.concurrent.Future
import scala.util.control.NonFatal

class AdminApi(
                mediaApi: MediaApi,
                resourceApi: ResourceApi,
                system: ActorSystem[Nothing],
                scanner: LocalMediaScanner,
                config: AmonyConfig
) extends Logging {

  implicit val scheduler      = system.scheduler
  implicit val monixScheduler = monix.execution.Scheduler.Implicits.global

  def scanLibrary()(implicit timeout: Timeout): Unit = {

    logger.info("Scanning library")

    mediaApi
      .getAll()
      .foreach { loadedFromStore =>
        val (deleted, newAndMoved) = scanner.scanMediaInDirectory(loadedFromStore)
        val upsert                 = Consumer.foreachTask[Media](m => Task { mediaApi.upsertMedia(m) })

        val delete = Consumer.foreachTask[Media](m =>
          Task {
            logger.info(s"Detected deleted file: ${m.fileInfo.relativePath}")
            mediaApi.deleteMedia(m.id, deleteResource = false)
          }
        )

        deleted.consumeWith(delete).runSyncUnsafe()
        newAndMoved.consumeWith(upsert).runSyncUnsafe()

      }(system.executionContext)
  }

  def reGeneratePreviewSprites()(implicit timeout: Timeout): Unit = {
    mediaApi.getAll().foreach { medias =>
      medias.foreach { m =>
        logger.info(s"generating thumbnail previews for '${m.fileName()}'")
        try {
          FFMpeg.createThumbnailTile(
            inputFile      = m.resolvePath(config.media.mediaPath).toAbsolutePath,
            outputDir      = config.media.resourcePath,
            outputBaseName = Some(s"${m.id}-timeline"),
            overwrite      = false
          ).runSyncUnsafe()
        } catch {
          case NonFatal(e) =>
            logger.warn(s"Failed to generate preview sprite for ${m.fileName()}", e)
        }
      }
    }
  }

  def regeneratePreviewForMedia(media: Media)(implicit timeout: Timeout): Unit = {
    logger.info(s"re-generating previews for '${media.fileInfo.relativePath}'")
    resourceApi.createFragments(media)
  }

  def reGenerateAllPreviews()(implicit timeout: Timeout): Unit =
    mediaApi.getAll().foreach { medias => medias.foreach(regeneratePreviewForMedia) }

  def verifyHashes()(implicit timeout: Timeout): Unit = {

    mediaApi.getAll().foreach { medias =>
      logger.info("Verifying all file hashes...")

      val start = System.currentTimeMillis()

      medias.foreach { m =>

        val hash = config.media.hashingAlgorithm.createHash(config.media.mediaPath.resolve(m.fileInfo.relativePath))

        if (hash != m.fileInfo.hash)
          logger.warn(s"hash not equal: ${hash} != ${m.fileInfo.hash}")
      }

      logger.info(s"Done ... in ${System.currentTimeMillis() - start} millis")
    }
  }

  def updateHashes()(implicit timeout: Timeout): Unit = {

    mediaApi.getAll().foreach { medias =>
      logger.info("Updating hashes ...")

      medias.foreach { m =>
        val hash = config.media.hashingAlgorithm.createHash(config.media.mediaPath.resolve(m.fileInfo.relativePath))

        if (hash != m.fileInfo.hash) {

          logger.info(s"Updating hash from '${m.fileInfo.hash}' to '$hash' for '${m.fileName()}''")
          mediaApi.upsertMedia(m.copy(id = hash, fileInfo = m.fileInfo.copy(hash = hash)))
          mediaApi.deleteMedia(id = m.id, deleteResource = false)
        }
      }

      logger.info("Done ...")
    }
  }

  def exportLibrary()(implicit timeout: Timeout): Future[String] = {

    val objectMapper = JacksonObjectMapperProvider.get(system).getOrCreate("media-export", None)
    val file         = config.media.getIndexPath().resolve("export.json").toFile

    mediaApi.getAll().map { medias =>
      objectMapper.createGenerator(file, JsonEncoding.UTF8).writeObject(medias)

      val out = new ByteArrayOutputStream()
      objectMapper.createGenerator(out, JsonEncoding.UTF8).useDefaultPrettyPrinter().writeObject(medias)
      out.toString()
    }
  }

  def convertNonStreamableVideos(): Unit =
    ConvertNonStreamableVideos.convertNonStreamableVideos(config.media, mediaApi, this)
}

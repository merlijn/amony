package nl.amony.api

import akka.actor.typed.ActorSystem
import akka.serialization.jackson.JacksonObjectMapperProvider
import akka.util.Timeout
import com.fasterxml.jackson.core.JsonEncoding
import monix.eval.Task
import monix.reactive.Consumer
import nl.amony.AmonyConfig
import nl.amony.actor.Message
import nl.amony.actor.media.MediaLibProtocol.Media
import nl.amony.actor.resources.{MediaScanner, ResourcesProtocol}
import nl.amony.actor.util.ConvertNonStreamableVideos
import nl.amony.lib.ffmpeg.FFMpeg
import scribe.Logging

import java.io.ByteArrayOutputStream
import scala.concurrent.Future

class AdminApi(mediaApi: MediaApi,
               system: ActorSystem[Message],
               scanner: MediaScanner,
               config: AmonyConfig) extends Logging {

  implicit val scheduler            = system.scheduler
  implicit val monixScheduler       = monix.execution.Scheduler.Implicits.global

  def scanLibrary()(implicit timeout: Timeout): Unit = {

    mediaApi
      .getAll()
      .foreach { loadedFromStore =>
        val (deleted, newAndMoved) = scanner.scanMediaInDirectory(config.media, loadedFromStore)
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

  def generateThumbnailPreviews()(implicit timeout: Timeout): Unit = {
    mediaApi.getAll().foreach { medias =>
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
    system.tell(ResourcesProtocol.CreateFragments(media, true))
  }

  def regenerateAllPreviews()(implicit timeout: Timeout): Unit =
    mediaApi.getAll().foreach { medias => medias.foreach(regeneratePreviewForMedia) }

  def verifyHashes()(implicit timeout: Timeout): Unit = {

    mediaApi.getAll().foreach { medias =>
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

    mediaApi.getAll().foreach { medias =>
      logger.info("Updating hashes ...")

      medias.foreach { m =>
        val hash = config.media.hashingAlgorithm.generateHash(config.media.mediaPath.resolve(m.fileInfo.relativePath))

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
    val file = config.media.indexPath.resolve("export.json").toFile

    mediaApi.getAll().map { medias =>
      objectMapper.createGenerator(file, JsonEncoding.UTF8).writeObject(medias)

      val out = new ByteArrayOutputStream()
      objectMapper.createGenerator(out, JsonEncoding.UTF8).useDefaultPrettyPrinter().writeObject(medias)
      out.toString()
    }
  }

  def convertNonStreamableVideos(): Unit =
    ConvertNonStreamableVideos.convertNonStreamableVideos(config, mediaApi, this)
}
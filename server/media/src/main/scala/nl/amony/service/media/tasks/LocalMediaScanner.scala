package nl.amony.service.media.tasks

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import nl.amony.lib.akka.EventProcessing
import nl.amony.service.media.MediaService
import nl.amony.service.resources.ResourceConfig.LocalResourcesConfig
import nl.amony.service.resources.local.LocalResourcesStore
import nl.amony.service.resources.local.LocalResourcesStore._
import scribe.Logging

import java.nio.file.Path

object LocalMediaScanner  {

  def behavior(config: LocalResourcesConfig): Behavior[(Long, LocalResourceEvent)] =
    Behaviors.setup { context =>

      val scanner = new LocalMediaScanner(config.mediaPath, new MediaService(context.system))

      EventProcessing.processAtLeastOnce[LocalResourceEvent](
          LocalResourcesStore.persistenceId(config.id),
          "scanner",
          e => scanner.processEvent(e)
        )
    }
}

class LocalMediaScanner(mediaPath: Path, mediaService: MediaService) extends Logging {

  implicit val monixScheduler = monix.execution.Scheduler.Implicits.global

  def processEvent(e: LocalResourceEvent): Unit = e match {
    case FileAdded(resource) =>
      logger.info(s"Scanning new media: ${resource.relativePath}")
      val relativePath = Path.of(resource.relativePath)
      val media = ScanMedia.scanMedia("test", mediaPath, relativePath, resource.hash).runSyncUnsafe()
      mediaService.upsertMedia(media)
    case FileDeleted(hash, relativePath) =>
      logger.info(s"Media was deleted: $relativePath")
      mediaService.deleteMedia(hash, false)
    case FileMoved(hash, oldPath, newPath) =>
    // ignore for now, send rename command?
  }
}


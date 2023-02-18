package nl.amony.service.media.tasks

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import cats.effect.IO
import cats.effect.unsafe.IORuntime
import nl.amony.lib.akka.EventProcessing
import nl.amony.service.media.MediaService
import nl.amony.service.resources.ResourceBucket
import nl.amony.service.resources.ResourceConfig.LocalResourcesConfig
import nl.amony.service.resources.local.DirectoryScanner.{ResourceEvent, ResourceAdded, ResourceDeleted, ResourceMoved}
import nl.amony.service.resources.local.LocalResourcesStore
import scribe.Logging

import java.nio.file.Path

object LocalMediaScanner  {

  def behavior(config: LocalResourcesConfig, resourceBuckets: Map[String, ResourceBucket], mediaService: MediaService): Behavior[(Long, ResourceEvent)] =
    Behaviors.setup { context =>

      val scanner = new LocalMediaScanner(resourceBuckets, mediaService)

      EventProcessing.processAtLeastOnce[ResourceEvent](
          LocalResourcesStore.persistenceId(config.id),
          "scanner",
          e => scanner.processEvent(e)
        )
    }
}

class LocalMediaScanner(resourceBuckets: Map[String, ResourceBucket], mediaService: MediaService) extends Logging {

  implicit val ioRuntime = IORuntime.global

  val bucketId = "local"

  def processEvent(e: ResourceEvent): Unit = e match {
    case ResourceAdded(resource) =>
      logger.info(s"Start scanning new media: ${resource.relativePath}")
      val relativePath = Path.of(resource.relativePath)

      ScanMedia
        .scanMedia(resourceBuckets(bucketId), resource, bucketId)
        .flatMap(media => IO.fromFuture(IO(mediaService.upsertMedia(media)))).unsafeRunSync()

      logger.info(s"Done scanning new media: ${resource.relativePath}")

    case ResourceDeleted(hash, relativePath) =>
      logger.info(s"Media was deleted: $relativePath")
      mediaService.deleteMedia(hash, false)
    case ResourceMoved(hash, oldPath, newPath) =>
    // ignore for now, send rename command?
  }
}


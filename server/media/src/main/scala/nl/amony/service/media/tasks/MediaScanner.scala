package nl.amony.service.media.tasks

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import nl.amony.service.media.MediaService
import nl.amony.service.resources.ResourceBucket
import nl.amony.service.resources.events.{ResourceAdded, ResourceDeleted, ResourceEvent, ResourceMoved}
import scribe.Logging

class MediaScanner(resourceBuckets: Map[String, ResourceBucket], mediaService: MediaService) extends Logging {

  implicit val ioRuntime = IORuntime.global

  def processEvent(e: ResourceEvent): Unit = e match {
    case ResourceAdded(resource) =>
      logger.info(s"Start scanning new media: ${resource.path}")

      ScanMedia
        .scanMedia(resourceBuckets(resource.bucketId), resource, resource.bucketId)
        .flatMap(media => IO.fromFuture(IO(mediaService.upsertMedia(media)))).unsafeRunSync()

    case ResourceDeleted(resource) =>
      logger.info(s"Media was deleted: ${resource.hash}")
      mediaService.deleteMedia(resource.hash, false)

    case ResourceMoved(resource, oldPath) =>
    // ignore for now, send rename command?
  }
}


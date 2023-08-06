package nl.amony.service.media.tasks

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import nl.amony.service.media.MediaServiceImpl
import nl.amony.service.media.api.DeleteById
import nl.amony.service.resources.api.events.{ResourceAdded, ResourceDeleted, ResourceEvent, ResourceMoved}
import scribe.Logging

class MediaScanner(mediaService: MediaServiceImpl) extends Logging {

  implicit val ioRuntime = IORuntime.global

  def processEvent(e: ResourceEvent): Unit = e match {
    case ResourceAdded(resource) =>
      logger.info(s"Start scanning new media: ${resource.path}")

      ScanMedia
        .asMedia(resource)
        .flatMap(media => IO.fromFuture(IO(mediaService.upsertMedia(media)))).unsafeRunSync()

    case ResourceDeleted(resource) =>
      logger.info(s"Media was deleted: ${resource.hash}")
      val req = DeleteById(resource.hash)
      mediaService.deleteById(req)

    case ResourceMoved(resource, oldPath) =>
    // ignore for now, send rename command?
  }
}


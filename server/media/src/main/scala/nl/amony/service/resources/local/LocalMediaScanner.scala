package nl.amony.service.resources.local

import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.util.Timeout
import nl.amony.lib.akka.AtLeastOnceProcessor
import nl.amony.service.media.MediaConfig.LocalResourcesConfig
import nl.amony.service.media.actor.MediaLibProtocol._
import nl.amony.service.resources.local.LocalResourcesStore._
import nl.amony.service.resources.local.tasks.ScanMedia
import scribe.Logging

import java.nio.file.Path
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

object LocalMediaScanner extends Logging {

  implicit val monixScheduler = monix.execution.Scheduler.Implicits.global

  def behavior(config: LocalResourcesConfig, mediaLib: ActorRef[MediaCommand]): Behavior[(Long, LocalResourceEvent)] =
    Behaviors.setup { context =>

      implicit val scheduler = context.system.scheduler
      implicit val timeout: Timeout = Timeout(5.seconds)

      def processEvent(e: LocalResourceEvent): Unit = e match {
        case FileAdded(resource) =>
          logger.info(s"Scanning new media: ${resource.relativePath}")
          val relativePath = Path.of(resource.relativePath)
          val media = ScanMedia.scanMedia(config.mediaPath, relativePath, resource.hash).runSyncUnsafe()
          Await.result(mediaLib.ask[Boolean](ref => UpsertMedia(media, ref)), timeout.duration)
        case FileDeleted(hash, relativePath) =>
          logger.info(s"Media was deleted: $relativePath")
          Await.result(mediaLib.ask[Boolean](ref => RemoveMedia(hash, false, ref)), timeout.duration)
        case FileMoved(hash, oldPath, newPath) =>
        // ignore for now, send rename command?
      }

      AtLeastOnceProcessor
        .process[LocalResourceEvent](LocalResourcesStore.persistenceId(config.id), "scanner", processEvent _)
    }
}



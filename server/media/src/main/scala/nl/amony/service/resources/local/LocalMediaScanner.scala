package nl.amony.service.resources.local

import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.util.Timeout
import monix.eval.Task
import nl.amony.lib.akka.AtLeastOnceProcessor
import nl.amony.lib.ffmpeg.FFMpeg
import nl.amony.service.media.MediaConfig.LocalResourcesConfig
import nl.amony.service.media.actor.MediaLibProtocol._
import nl.amony.service.resources.local.LocalResourcesStore.{LocalResourceEvent, FileAdded, FileDeleted, FileMoved}
import scribe.Logging

import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{Files, Path}
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class LocalMediaScanner(config: LocalResourcesConfig) extends Logging {

  private[resources] def scanMedia(mediaPath: Path, hash: Option[String]): Task[Media] = {

    FFMpeg
      .ffprobe(mediaPath, false, config.ffprobeTimeout)
      .map { case probe =>
        val fileHash = hash.getOrElse(config.hashingAlgorithm.createHash(mediaPath))

        val mainVideoStream =
          probe.firstVideoStream.getOrElse(throw new IllegalStateException(s"No video stream found for: ${mediaPath}"))

        logger.debug(mainVideoStream.toString)

        probe.debugOutput.foreach { debug =>
          if (!debug.isFastStart)
            logger.warn(s"Video is not optimized for streaming: ${mediaPath}")
        }

        val fileAttributes = Files.readAttributes(mediaPath, classOf[BasicFileAttributes])

        val timeStamp = mainVideoStream.durationMillis / 3

        val fileInfo = FileInfo(
          relativePath     = config.mediaPath.relativize(mediaPath).toString,
          hash             = fileHash,
          size             = fileAttributes.size(),
          creationTime     = fileAttributes.creationTime().toMillis,
          lastModifiedTime = fileAttributes.lastModifiedTime().toMillis
        )

        val videoInfo = MediaInfo(
          mainVideoStream.fps,
          mainVideoStream.codec_name,
          mainVideoStream.durationMillis,
          (mainVideoStream.width, mainVideoStream.height)
        )

        val fragmentLength = config.fragments.defaultFragmentLength.toMillis

        Media(
          id                 = fileHash,
          uploader           = "0",
          uploadTimestamp    = System.currentTimeMillis(),
          meta = MediaMeta(
            title = None,
            comment = None,
            tags = Set.empty
          ),
          fileInfo           = fileInfo,
          videoInfo          = videoInfo,
          thumbnailTimestamp = timeStamp,
          fragments          = List(Fragment(timeStamp, timeStamp + fragmentLength, None, List.empty)),
        )
      }
  }

  implicit val monixScheduler = monix.execution.Scheduler.Implicits.global

  def behavior(mediaLib: ActorRef[MediaCommand]): Behavior[(Long, LocalResourceEvent)] =
    Behaviors.setup { context =>

      implicit val scheduler = context.system.scheduler
      implicit val timeout: Timeout = Timeout(5.seconds)

      def processEvent(e: LocalResourceEvent): Unit = e match {
        case FileAdded(resource) =>
          logger.info(s"Scanning new media: ${resource.relativePath}")
          val mediaPath = config.mediaPath.resolve(resource.relativePath)
          val media = scanMedia(mediaPath, Some(resource.hash)).runSyncUnsafe()
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

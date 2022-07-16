package nl.amony.service.resources.local.tasks

import monix.eval.Task
import nl.amony.lib.ffmpeg.FFMpeg
import nl.amony.service.media.actor.MediaLibProtocol.{FileInfo, Fragment, Media, MediaInfo, MediaMeta}
import scribe.Logging

import java.nio.file.{Files, Path}
import java.nio.file.attribute.BasicFileAttributes

object ScanMedia extends Logging {
  def scanMedia(
       basePath: Path,
       relativeMediaPath: Path,
       hash: String,
       fragmentLength: Long = 3000): Task[Media] = {

    val mediaPath = basePath.resolve(relativeMediaPath)

    FFMpeg
      .ffprobe(mediaPath, false)
      .map { case probe =>

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
          relativePath     = relativeMediaPath.toString,
          hash             = hash,
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

        Media(
          id                 = hash,
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
}

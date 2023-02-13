package nl.amony.service.media.tasks

import cats.effect.IO
import nl.amony.lib.ffmpeg.FFMpeg
import nl.amony.service.media.MediaProtocol._
import nl.amony.service.media.api.protocol.{MediaMeta, ResourceInfo}
import nl.amony.service.media.web.MediaWebModel.MediaInfo
import scribe.Logging

import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{Files, Path}

object ScanMedia extends Logging {

  def scanMedia(
       bucketId: String,
       basePath: Path,
       relativeMediaPath: Path,
       hash: String): IO[Media] = {

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

        val fileInfo = ResourceInfo(
          bucketId         = bucketId,
          relativePath     = relativeMediaPath.toString,
          hash             = hash,
          sizeInBytes      = fileAttributes.size(),
        )

        val videoInfo = MediaInfo(
          mainVideoStream.width,
          mainVideoStream.height,
          mainVideoStream.fps,
          mainVideoStream.durationMillis,
          mainVideoStream.codec_name,
        )

        val mediaId = hash

        Media(
          mediaId            = mediaId,
          uploader           = "0",
          uploadTimestamp    = System.currentTimeMillis(),
          meta = MediaMeta(
            title   = None,
            comment = None,
            tags    = Seq.empty
          ),
          resourceInfo       = fileInfo,
          mediaInfo          = videoInfo,
          thumbnailTimestamp = timeStamp,
        )
      }
  }
}

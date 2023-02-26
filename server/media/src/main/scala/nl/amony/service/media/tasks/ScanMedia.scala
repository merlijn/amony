package nl.amony.service.media.tasks

import cats.effect.IO
import nl.amony.service.media.api._
import nl.amony.service.resources.ResourceBucket
import nl.amony.service.resources.events.Resource
import scribe.Logging

object ScanMedia extends Logging {

  def scanMedia(
       resourceBucket: ResourceBucket,
       resource: Resource,
       bucketId: String): IO[Media] = {

    IO.fromFuture(IO(resourceBucket.getFFProbeOutput(resource.hash)))
      .map {
        case None        => throw new IllegalStateException(s"Resource does not exist: ${resource.path}")
        case Some(probe) =>

        val mainVideoStream =
          probe.firstVideoStream.getOrElse(throw new IllegalStateException(s"No video stream found for: ${resource.path}"))

        logger.debug(mainVideoStream.toString)

        probe.debugOutput.foreach { debug =>
          if (!debug.isFastStart)
            logger.warn(s"Video is not optimized for streaming: ${resource.path}")
        }

        val timeStamp = mainVideoStream.durationMillis / 3

        val fileInfo = ResourceInfo(
          bucketId         = bucketId,
          relativePath     = resource.path,
          hash             = resource.hash,
          sizeInBytes      = resource.size,
        )

        val videoInfo = MediaInfo(
          mainVideoStream.codec_name,
          mainVideoStream.width,
          mainVideoStream.height,
          mainVideoStream.fps.toFloat,
          mainVideoStream.durationMillis,
        )

        val mediaId = resource.hash

        Media(
          mediaId            = mediaId,
          userId             = "0",
          createdTimestamp   = System.currentTimeMillis(),
          meta = MediaMeta(
            title   = None,
            comment = None,
            tags    = Seq.empty
          ),
          resourceInfo       = fileInfo,
          mediaInfo          = videoInfo,
          thumbnailTimestamp = timeStamp,
        )
      }.onError {
        e => IO { logger.warn("Exception while scanning media", e) }
      }
  }
}

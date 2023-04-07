package nl.amony.service.media.tasks

import cats.effect.IO
import nl.amony.lib.cats.FutureOps
import nl.amony.service.media.api._
import nl.amony.service.resources.{ImageMeta, ResourceBucket, VideoMeta}
import nl.amony.service.resources.events.Resource
import scribe.Logging

object ScanMedia extends Logging {

  def scanMedia(
       resourceBucket: ResourceBucket,
       resource: Resource,
       bucketId: String): IO[Media] = {

    resourceBucket.getResourceMeta(resource.hash).toIO.flatMap {
      case Some(image: ImageMeta) => handleImage(image, resource, bucketId)
      case Some(video: VideoMeta) => handleVideo(video, resource, bucketId)
      case _                      => handleUnkown(resource)
    }
  }

  def handleUnkown(resource: Resource) = {
    logger.info(s"Failed to get get content type for resource: ${resource.path}")
    IO.raiseError(new IllegalStateException())
  }

  def handleImage(meta: ImageMeta,
                  resource: Resource,
                  bucketId: String): IO[Media] = {

    IO {
      val fileInfo = ResourceInfo(
        bucketId     = bucketId,
        relativePath = resource.path,
        hash         = resource.hash,
        sizeInBytes  = resource.size,
      )

      val mediaInfo = MediaInfo(
        mediaType        = meta.contentType,
        width            = meta.width,
        height           = meta.height,
        fps              = 0f,
        durationInMillis = 0,
      )

      Media(
        mediaId = resource.hash,
        mediaType = meta.contentType,
        userId = "0",
        createdTimestamp = System.currentTimeMillis(),
        meta = MediaMeta(
          title   = None,
          comment = None,
          tags    = Seq.empty
        ),
        resourceInfo = fileInfo,
        mediaInfo    = mediaInfo,
        thumbnailTimestamp = 0,
        availableFormats = List.empty
      )
    }
  }

  def handleVideo(meta: VideoMeta,
                  resource: Resource,
                  bucketId: String): IO[Media] = {
    IO {
//        probe.debugOutput.foreach { debug =>
//          if (!debug.isFastStart)
//            logger.warn(s"Video is not optimized for streaming: ${resource.path}")
//        }

        val timeStamp = meta.durationInMillis / 3

        val fileInfo = ResourceInfo(
          bucketId = bucketId,
          relativePath = resource.path,
          hash = resource.hash,
          sizeInBytes = resource.size,
        )

        val mediaInfo = MediaInfo(
          mediaType        = resource.contentType.get,
          width            = meta.width,
          height           = meta.height,
          fps              = meta.fps,
          durationInMillis = meta.durationInMillis,
        )

        val mediaId = resource.hash

        Media(
          mediaId = mediaId,
          mediaType = resource.contentType.get,
          userId = "0",
          createdTimestamp = System.currentTimeMillis(),
          meta = MediaMeta(
            title = None,
            comment = None,
            tags = Seq.empty
          ),
          resourceInfo       = fileInfo,
          mediaInfo          = mediaInfo,
          thumbnailTimestamp = timeStamp,
        )
      }.onError { e => IO { logger.warn("Exception while scanning media", e) } }
  }
}

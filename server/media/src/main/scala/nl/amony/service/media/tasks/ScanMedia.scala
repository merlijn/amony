package nl.amony.service.media.tasks

import cats.effect.IO
import nl.amony.service.media.api
import nl.amony.service.resources.ResourceBucket
import nl.amony.service.resources.api.{ImageMeta, ResourceInfo, VideoMeta}
import scribe.Logging

object ScanMedia extends Logging {

  def scanMedia(
       resourceBucket: ResourceBucket,
       resource: ResourceInfo,
       bucketId: String): IO[api.Media] = {

    resource.contentMeta match {
      case image: ImageMeta => handleImage(image, resource, bucketId)
      case video: VideoMeta => handleVideo(video, resource, bucketId)
      case _                => handleUnkown(resource)
    }

//    resourceBucket.getResourceMeta(resource.hash).flatMap {
//      case Some(image: ImageMeta) => handleImage(image, resource, bucketId)
//      case Some(video: VideoMeta) => handleVideo(video, resource, bucketId)
//      case _                      => handleUnkown(resource)
//    }
  }

  def handleUnkown(resource: ResourceInfo) = {
    logger.info(s"Failed to get get content type for resource: ${resource.path}")
    IO.raiseError(new IllegalStateException())
  }

  def handleImage(meta: ImageMeta,
                  resource: ResourceInfo,
                  bucketId: String): IO[api.Media] = {

    IO {
      val fileInfo = api.ResourceInfo(
        bucketId     = bucketId,
        relativePath = resource.path,
        hash         = resource.hash,
        sizeInBytes  = resource.size,
      )

      api.Media(
        mediaId = resource.hash,
        mediaType = meta.contentType,
        userId = "0",
        createdTimestamp = System.currentTimeMillis(),
        meta = api.MediaMeta(
          title   = None,
          comment = None,
          tags    = Seq.empty
        ),
        resourceInfo = fileInfo,
        mediaInfo    = meta,
        thumbnailTimestamp = 0,
        availableFormats = List.empty
      )
    }
  }

  def handleVideo(meta: VideoMeta,
                  resource: ResourceInfo,
                  bucketId: String): IO[api.Media] = {
    IO {
//        probe.debugOutput.foreach { debug =>
//          if (!debug.isFastStart)
//            logger.warn(s"Video is not optimized for streaming: ${resource.path}")
//        }

        val timeStamp = meta.durationInMillis / 3

        val fileInfo = api.ResourceInfo(
          bucketId = bucketId,
          relativePath = resource.path,
          hash = resource.hash,
          sizeInBytes = resource.size,
        )

        val mediaId = resource.hash

      api.Media(
          mediaId = mediaId,
          mediaType = resource.contentType.get,
          userId = "0",
          createdTimestamp = System.currentTimeMillis(),
          meta = api.MediaMeta(
            title = None,
            comment = None,
            tags = Seq.empty
          ),
          resourceInfo       = fileInfo,
          mediaInfo          = meta,
          thumbnailTimestamp = timeStamp,
        )
      }.onError { e => IO { logger.warn("Exception while scanning media", e) } }
  }
}

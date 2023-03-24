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

    resource.contentType.flatMap(_.split('/').headOption) match {
      case Some("video") => handleVideo(resourceBucket, resource, bucketId)
      case Some("image") => handleImage(resourceBucket, resource, bucketId)
      case _             => handleUnkown(resource)
    }
  }

  def handleUnkown(resource: Resource) = {
    logger.info(s"Failed to get get content type for resource: ${resource.path}")
    IO.raiseError(new IllegalStateException())
  }

  def handleImage(resourceBucket: ResourceBucket,
                  resource: Resource,
                  bucketId: String): IO[Media] = {

    IO.fromFuture(IO(resourceBucket.getImageMetaData(resource.hash))).map {
      case None       => throw new IllegalStateException(s"Resource does not exist: ${resource.path}")
      case Some(meta) =>

        val fileInfo = ResourceInfo(
          bucketId     = bucketId,
          relativePath = resource.path,
          hash         = resource.hash,
          sizeInBytes  = resource.size,
        )

        val mediaInfo = MediaInfo(
          mediaType        = resource.contentType.get,
          width            = meta.geometry.width,
          height           = meta.geometry.height,
          fps              = 0f,
          durationInMillis = 0,
        )

        Media(
          mediaId = resource.hash,
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
        )
    }
  }

  def handleVideo(resourceBucket: ResourceBucket,
                  resource: Resource,
                  bucketId: String): IO[Media] = {
    IO.fromFuture(IO(resourceBucket.getFFProbeOutput(resource.hash)))
      .map {
        case None        => throw new IllegalStateException(s"Resource does not exist: ${resource.path}")
        case Some(probe) =>

          val mainVideoStream =
            probe.firstVideoStream.getOrElse(throw new IllegalStateException(s"No video stream found for: ${resource.path}"))

          probe.debugOutput.foreach { debug =>
            if (!debug.isFastStart)
              logger.warn(s"Video is not optimized for streaming: ${resource.path}")
          }

          val timeStamp = mainVideoStream.durationMillis / 3

          val fileInfo = ResourceInfo(
            bucketId = bucketId,
            relativePath = resource.path,
            hash = resource.hash,
            sizeInBytes = resource.size,
          )

          val mediaInfo = MediaInfo(
            mediaType        = resource.contentType.get,
            width            = mainVideoStream.width,
            height           = mainVideoStream.height,
            fps              = mainVideoStream.fps.toFloat,
            durationInMillis = mainVideoStream.durationMillis,
          )

          val mediaId = resource.hash

          Media(
            mediaId = mediaId,
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
      }.onError {
      e => IO {
        logger.warn("Exception while scanning media", e)
      }
    }
  }
}

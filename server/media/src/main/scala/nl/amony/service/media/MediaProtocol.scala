package nl.amony.service.media

import java.nio.file.Path

object MediaProtocol {

  sealed trait ErrorResponse

  case class MediaNotFound(id: String)      extends ErrorResponse
  case class InvalidCommand(reason: String) extends ErrorResponse

  // -- State
  case class State(media: Map[String, Media])

  case class ResourceInfo(
      bucketId: String,
      relativePath: String,
      hash: String,
      size: Long
  ) {
    def extension: String = relativePath.split('.').last
  }

  case class MediaInfo(
    fps: Double,
    videoCodec: String,
    duration: Long,
    resolution: (Int, Int)
  )

  case class MediaMeta(
    title: Option[String],
    comment: Option[String],
    tags: Set[String]
  )

  case class Media(
    id: String,
    uploader: String,
    uploadTimestamp: Long,
    resourceInfo: ResourceInfo,
    mediaInfo: MediaInfo,
    meta: MediaMeta,
    thumbnailTimestamp: Long,
  ) {
    def resolvePath(baseDir: Path): Path = baseDir.resolve(resourceInfo.relativePath)

    def width: Int = mediaInfo.resolution._1
    def height: Int = mediaInfo.resolution._2

    def fileName(): String = {
      val slashIdx = resourceInfo.relativePath.lastIndexOf('/')
      val dotIdx   = resourceInfo.relativePath.lastIndexOf('.')

      val startIdx = if (slashIdx >= 0) slashIdx + 1 else 0
      val endIdx   = if (dotIdx >= 0) dotIdx else resourceInfo.relativePath.length

      resourceInfo.relativePath.substring(startIdx, endIdx)
    }
  }
}

package nl.amony.service.media

import nl.amony.service.media.api.protocol.{MediaMeta, ResourceInfo}
import nl.amony.service.media.web.MediaWebModel.MediaInfo

import java.nio.file.Path

object MediaProtocol {

  // -- State
  case class Media(
    mediaId: String,
    uploader: String,
    uploadTimestamp: Long,
    resourceInfo: ResourceInfo,
    mediaInfo: MediaInfo,
    meta: MediaMeta,
    thumbnailTimestamp: Long,
  ) {
    def resolvePath(baseDir: Path): Path = baseDir.resolve(resourceInfo.relativePath)

    def width: Int = mediaInfo.width
    def height: Int = mediaInfo.height

    def fileName(): String = {
      val slashIdx = resourceInfo.relativePath.lastIndexOf('/')
      val dotIdx   = resourceInfo.relativePath.lastIndexOf('.')

      val startIdx = if (slashIdx >= 0) slashIdx + 1 else 0
      val endIdx   = if (dotIdx >= 0) dotIdx else resourceInfo.relativePath.length

      resourceInfo.relativePath.substring(startIdx, endIdx)
    }
  }
}

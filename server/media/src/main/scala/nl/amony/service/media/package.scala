package nl.amony.service

import nl.amony.service.media.api.Media

package object media {
  implicit class MediaOps(media: Media) {

    def width: Int = media.mediaInfo.width
    def height: Int = media.mediaInfo.height

    def fileName(): String = {
      val slashIdx = media.resourceInfo.relativePath.lastIndexOf('/')
      val dotIdx = media.resourceInfo.relativePath.lastIndexOf('.')

      val startIdx = if (slashIdx >= 0) slashIdx + 1 else 0
      val endIdx = if (dotIdx >= 0) dotIdx else media.resourceInfo.relativePath.length

      media.resourceInfo.relativePath.substring(startIdx, endIdx)
    }
  }
}

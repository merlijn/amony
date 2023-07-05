package nl.amony.service

import nl.amony.service.media.api.Media
import nl.amony.service.resources.api.{ImageMeta, VideoMeta}

package object media {
  implicit class MediaOps(media: Media) {

    def width: Int = media.mediaInfo match {
      case VideoMeta(_, w, _, _, _, _) => w
      case ImageMeta(_, w, _, _)       => w
    }

    def height: Int = media.mediaInfo match {
      case VideoMeta(_, _, h, _, _, _) => h
      case ImageMeta(_, _, h, _) => h
    }

    def fileName(): String = {
      val slashIdx = media.resourceInfo.relativePath.lastIndexOf('/')
      val dotIdx = media.resourceInfo.relativePath.lastIndexOf('.')

      val startIdx = if (slashIdx >= 0) slashIdx + 1 else 0
      val endIdx = if (dotIdx >= 0) dotIdx else media.resourceInfo.relativePath.length

      media.resourceInfo.relativePath.substring(startIdx, endIdx)
    }
  }
}

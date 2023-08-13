package nl.amony.service.resources

import nl.amony.service.resources.api.{ImageMeta, ResourceInfo, VideoMeta}

package object web {

  extension (resource: ResourceInfo) {

    def width: Int = resource.contentMeta match {
      case VideoMeta(_, w, _, _, _, _) => w
      case ImageMeta(_, w, _, _) => w
    }

    def height: Int = resource.contentMeta match {
      case VideoMeta(_, _, h, _, _, _) => h
      case ImageMeta(_, _, h, _) => h
    }

    def durationInMillis() = resource.contentMeta match {
      case m: VideoMeta => m.durationInMillis
      case _ => 0L
    }

    def fileName(): String = {

      val slashIdx = resource.path.lastIndexOf('/')
      val dotIdx = resource.path.lastIndexOf('.')

      val startIdx = if (slashIdx >= 0) slashIdx + 1 else 0
      val endIdx = if (dotIdx >= 0) dotIdx else resource.path.length

      resource.path.substring(startIdx, endIdx)
    }
  }
}

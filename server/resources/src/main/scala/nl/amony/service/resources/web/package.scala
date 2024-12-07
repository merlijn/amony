package nl.amony.service.resources

import nl.amony.service.resources.api.{ImageMeta, ResourceInfo, VideoMeta}

package object web {

  extension (resource: ResourceInfo) {

    def width: Int = resource.contentMeta match
      case VideoMeta(w, _, _, _, _, _) => w
      case ImageMeta(w, _, _) => w
      case _ => 0

    def height: Int = resource.contentMeta match
      case VideoMeta(_, h, _, _, _, _) => h
      case ImageMeta(_, h, _) => h
      case _ => 0


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

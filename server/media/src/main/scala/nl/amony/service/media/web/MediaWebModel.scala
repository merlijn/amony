package nl.amony.service.media.web

object MediaWebModel {

  case class MediaMeta(
    title: Option[String],
    comment: Option[String],
    tags: List[String]
  )

  case class MediaInfo(
    width: Int,
    height: Int,
    fps: Double,
    duration: Long,
    mediaType: String
  )

  case class MediaUrls(
    originalResourceUrl: String,
    thumbnailUrl: String,
    previewThumbnailsUrl: Option[String],
  )

  case class ResourceInfo(
    hash: String,
    sizeInBytes: Long
  )

  case class Media(
    id: String,
    uploader: String,
    uploadTimestamp: Long,
    meta: MediaMeta,
    mediaInfo: MediaInfo,
    resourceInfo: ResourceInfo,
    urls: MediaUrls,
    highlights: List[Fragment],
  )

  case class Fragment(
     media_id: String,
     index: Int,
     range: (Long, Long),
     urls: List[String],
     comment: Option[String],
     tags: List[String]
   )
}

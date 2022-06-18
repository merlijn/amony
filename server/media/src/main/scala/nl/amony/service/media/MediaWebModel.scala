package nl.amony.service.media

object MediaWebModel {

  case class Range(
    from: Long,
    to: Long
  )

  case class Fragment(
     media_id: String,
     index: Int,
     range: Range,
     urls: List[String],
     comment: Option[String],
     tags: List[String]
  )

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
     codecName: String
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

  case class Video(
    id: String,
    uploader: String,
    uploadTimestamp: Long,
    meta: MediaMeta,
    mediaInfo: MediaInfo,
    resourceInfo: ResourceInfo,
    urls: MediaUrls,
    fragments: List[Fragment],
  )
}

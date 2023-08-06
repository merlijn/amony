package nl.amony.service.resources.web

object ResourceWebModel {

  case class UserMeta(
    title: Option[String],
    comment: Option[String],
    tags: List[String]
  )

  case class ResourceMeta(
    width: Int,
    height: Int,
    fps: Double,
    duration: Long,
    mediaType: String
  )

  case class ResourceUrls(
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
    userMeta: UserMeta,
    resourceMeta: ResourceMeta,
    resourceInfo: ResourceInfo,
    urls: ResourceUrls,
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

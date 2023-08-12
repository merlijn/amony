package nl.amony.service.resources.web

object ResourceWebModel {

  case class UserMeta(
    title: Option[String],
    comment: Option[String],
    tags: List[String]
  )

  case class ResourceMetaDto(
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

  case class ResourceInfoDto(
    hash: String,
    sizeInBytes: Long
  )

  case class ResourceDto(
    id: String,
    uploader: String,
    uploadTimestamp: Long,
    userMeta: UserMeta,
    resourceMeta: ResourceMetaDto,
    resourceInfo: ResourceInfoDto,
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

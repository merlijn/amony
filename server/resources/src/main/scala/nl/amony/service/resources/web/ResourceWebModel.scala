package nl.amony.service.resources.web

object ResourceWebModel {

  case class UserMetaDto(
    title: Option[String],
    description: Option[String],
    tags: List[String]
  )

  case class ResourceMetaDto(
    width: Int,
    height: Int,
    fps: Double,
    duration: Long,
  )

  case class ResourceUrlsDto(
    originalResourceUrl: String,
    thumbnailUrl: String,
    previewThumbnailsUrl: Option[String],
  )

  case class ResourceInfoDto(
    hash: String,
    sizeInBytes: Long
  )

  case class ResourceDto(
    bucketId: String,
    id: String,
    uploader: String,
    uploadTimestamp: Long,
    userMeta: UserMetaDto,
    contentType: String,
    resourceMeta: ResourceMetaDto,
    resourceInfo: ResourceInfoDto,
    urls: ResourceUrlsDto,
    highlights: List[FragmentDto],
  )

  case class FragmentDto(
     media_id: String,
     index: Int,
     range: (Long, Long),
     urls: List[String],
     comment: Option[String],
     tags: List[String]
   )
}

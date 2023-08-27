package nl.amony.service.resources.web

import io.circe.*

object ResourceWebModel {

  case class UserMetaDto(
    title: Option[String],
    description: Option[String],
    tags: List[String]
  ) derives Encoder.AsObject, Decoder

  case class ResourceMetaDto(
    width: Int,
    height: Int,
    fps: Double,
    duration: Long,
  ) derives Encoder.AsObject

  case class ResourceUrlsDto(
    originalResourceUrl: String,
    thumbnailUrl: String,
    previewThumbnailsUrl: Option[String],
  ) derives Encoder.AsObject

  case class ResourceInfoDto(
    hash: String,
    sizeInBytes: Long
  ) derives Encoder.AsObject

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
  ) derives Encoder.AsObject

  case class FragmentDto(
     media_id: String,
     index: Int,
     range: (Long, Long),
     urls: List[String],
     comment: Option[String],
     tags: List[String]
   ) derives Encoder.AsObject
}

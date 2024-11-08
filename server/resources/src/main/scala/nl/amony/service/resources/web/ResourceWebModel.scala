package nl.amony.service.resources.web

import io.circe.*

object ResourceWebModel {
  
  case class ThumbnailTimestampDto(
     timestampInMillis: Long
  ) derives Encoder.AsObject, Decoder

  case class UserMetaDto(
    title: Option[String],
    description: Option[String],
    tags: List[String]
  ) derives Encoder.AsObject, Decoder

  case class ResourceMetaDto(
    width: Int,
    height: Int,
    fps: Float,
    duration: Long,
  ) derives Encoder.AsObject

  case class ResourceUrlsDto(
    originalResourceUrl: String,
    thumbnailUrl: String,
    previewThumbnailsUrl: Option[String],
  ) derives Encoder.AsObject

  case class ResourceInfoDto(
    hash: String,
    sizeInBytes: Long,
    path: String
  ) derives Encoder.AsObject

  case class ResourceDto(
    bucketId: String,
    resourceId: String,
    uploader: String,
    uploadTimestamp: Long,
    userMeta: UserMetaDto,
    contentType: String,
    resourceMeta: ResourceMetaDto,
    resourceInfo: ResourceInfoDto,
    urls: ResourceUrlsDto,
    thumbnailTimestamp: Option[Long],
    highlights: List[FragmentDto],
  ) derives Encoder.AsObject

  case class FragmentDto(
    resourceId: String,
    index: Int,
    range: (Long, Long),
    urls: List[String],
    comment: Option[String],
    tags: List[String]
   ) derives Encoder.AsObject
}

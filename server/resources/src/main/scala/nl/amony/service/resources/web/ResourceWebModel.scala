package nl.amony.service.resources.web

import io.circe.*

object ResourceWebModel {
  
  case class ThumbnailTimestampDto(
     timestampInMillis: Long
  ) derives Codec, sttp.tapir.Schema

  case class UserMetaDto(
    title: Option[String],
    description: Option[String],
    tags: List[String]
  ) derives Codec, sttp.tapir.Schema

  case class ResourceMetaDto(
    width: Int,
    height: Int,
    fps: Float,
    duration: Long,
    codec: Option[String],
  ) derives Codec, sttp.tapir.Schema

  case class ResourceUrlsDto(
    originalResourceUrl: String,
    thumbnailUrl: String,
    previewThumbnailsUrl: Option[String],
  ) derives Codec, sttp.tapir.Schema

  case class ResourceInfoDto(
    hash: String,
    sizeInBytes: Long,
    path: String
  ) derives Codec, sttp.tapir.Schema

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
    clips: List[ClipDto],
  ) derives Codec, sttp.tapir.Schema

  case class ClipDto(
    resourceId: String,
    start: Long,
    end: Long,
    urls: List[String],
    description: Option[String],
    tags: List[String]
   ) derives Codec, sttp.tapir.Schema
}

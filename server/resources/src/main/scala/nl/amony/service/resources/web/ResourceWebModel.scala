package nl.amony.service.resources.web

import io.circe.*
import sttp.tapir.Schema
import sttp.tapir.Schema.annotations.customise

object ResourceWebModel {

  def required[T](s: Schema[T]) = s.copy(isOptional = false)
  
  case class ThumbnailTimestampDto(
     timestampInMillis: Long
  ) derives Codec, sttp.tapir.Schema

  case class UserMetaDto(
    title: Option[String],
    description: Option[String],
    @customise(required)
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
    @customise(required)
    clips: List[ClipDto],
  ) derives Codec, sttp.tapir.Schema

  case class ClipDto(
    resourceId: String,
    start: Long,
    end: Long,
    @customise(required)
    urls: List[String],
    description: Option[String],
    @customise(required)
    tags: List[String]
   ) derives Codec, sttp.tapir.Schema
}

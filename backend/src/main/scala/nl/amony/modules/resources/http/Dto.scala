package nl.amony.modules.resources.http

import io.circe.*
import sttp.tapir.Schema.SName
import sttp.tapir.Schema.annotations.customise
import sttp.tapir.{FieldName, Schema, SchemaType}

import nl.amony.modules.auth.api.UserId
import nl.amony.modules.resources.api.*
import nl.amony.modules.resources.api.{ImageProperties, ResourceInfo, VideoProperties}

def required[T](s: Schema[T]) = s.copy(isOptional = false)

case class BucketDto(bucketId: String, name: String, `type`: String) derives Codec, sttp.tapir.Schema

case class ThumbnailTimestampDto(timestampInMillis: Int) derives Codec, sttp.tapir.Schema

case class UserMetaDto(
  title: Option[String],
  description: Option[String],
  @customise(required)
  tags: List[String]
) derives Codec, sttp.tapir.Schema

case class BulkTagsUpdateDto(ids: List[String], tagsToRemove: List[String], tagsToAdd: List[String]) derives Codec, sttp.tapir.Schema

case class ResourceMetaDto(width: Int, height: Int, fps: Float, duration: Long, codec: Option[String]) derives Codec, sttp.tapir.Schema

case class ResourceUrlsDto(originalResourceUrl: String, thumbnailUrl: String, previewThumbnailsUrl: Option[String]) derives Codec, sttp.tapir.Schema

case class ResourceToolMetaDto(toolName: String, toolData: Json) derives Codec

object ResourceToolMetaDto {
  given schemaForCirceJsonAny: Schema[Json] = Schema.any[Json]
  given Schema[ResourceToolMetaDto]         = Schema.derived[ResourceToolMetaDto]
}

case class ResourceDto(
  bucketId: String,
  resourceId: String,
  hash: Option[String],
  sizeInBytes: Long,
  path: String,
  timeAdded: Long,
  timeLastModified: Option[Long],
  userId: String,
  title: Option[String],
  description: Option[String],
  @customise(required)
  tags: List[String],
  contentType: String,
  contentMeta: ResourceMetaDto,
  urls: ResourceUrlsDto,
  thumbnailTimestamp: Option[Int],
  @customise(required)
  clips: List[ClipDto]
) derives Codec, sttp.tapir.Schema {

  def toDomain(): ResourceInfo = {

    ResourceInfo(
      bucketId           = bucketId,
      resourceId         = ResourceId(resourceId),
      userId             = UserId(userId),
      path               = path,
      hash               = hash,
      size               = sizeInBytes,
      contentType        = Some(contentType),
      contentMeta        = None,
      tags               = tags.toSet,
      timeAdded          = Some(timeAdded),
      title              = title,
      description        = description,
      thumbnailTimestamp = thumbnailTimestamp
    )
  }
}

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

def toDto(resource: ResourceInfo): ResourceDto = {

  // Default resolution key used in public URLs
  val defaultResolutionKey = "s"

  val durationInMillis = resource.basicContentProperties match {
    case Some(m: VideoProperties) => m.durationInMillis
    case _                        => 0
  }

  // Thumbnail timestamp: use saved value, fall back to 1/3 of duration
  val thumbnailTimestamp: Int = resource.thumbnailTimestamp.getOrElse(durationInMillis / 3)

  val contentMeta: ResourceMetaDto = resource.basicContentProperties match {
    case Some(ImageProperties(width, height, _)) => ResourceMetaDto(width = width, height = height, duration = 0, fps = 0, codec = None)

    case Some(VideoProperties(width, height, fps, duration, codec)) =>
      ResourceMetaDto(width = width, height = height, duration = duration, fps = fps, codec = codec)

    case None => ResourceMetaDto(width = 0, height = 0, duration = 0, fps = 0, codec = None)
  }

  val urls = ResourceUrlsDto(
    originalResourceUrl  = s"/api/resources/${resource.bucketId}/${resource.resourceId}/content",
    thumbnailUrl         = s"/api/resources/${resource.bucketId}/${resource.resourceId}/thumb_$defaultResolutionKey.webp",
    previewThumbnailsUrl = Some(s"/api/resources/${resource.bucketId}/${resource.resourceId}/timeline.vtt")
  )

  // A preview clip starting at the thumbnail timestamp, capped at 3 seconds and the video length
  val thumbnailClip = resource.basicContentProperties match {
    case Some(_: VideoProperties) =>
      val start    = thumbnailTimestamp.toLong
      val end      = Math.min(contentMeta.duration, start + 3000L)
      val clipUrls = List(s"/api/resources/${resource.bucketId}/${resource.resourceId}/clip_$defaultResolutionKey.mp4")
      Some(ClipDto(resourceId = resource.resourceId, start = start, end = end, urls = clipUrls, description = None, tags = List.empty))
    case _ =>
      None
  }

  ResourceDto(
    bucketId           = resource.bucketId,
    resourceId         = resource.resourceId,
    hash               = resource.hash,
    sizeInBytes        = resource.size,
    path               = resource.path,
    timeAdded          = resource.timeAdded.getOrElse(0L),
    timeLastModified   = resource.timeLastModified,
    userId             = resource.userId,
    title              = resource.title,
    description        = resource.description,
    tags               = resource.tags.toList,
    contentType        = resource.contentType.getOrElse("application/octet-stream"),
    contentMeta        = contentMeta,
    urls               = urls,
    thumbnailTimestamp = Some(thumbnailTimestamp),
    clips              = thumbnailClip.toList
  )
}

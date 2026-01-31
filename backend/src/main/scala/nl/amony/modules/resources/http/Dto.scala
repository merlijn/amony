package nl.amony.modules.resources.http

import io.circe.*
import sttp.tapir.Schema.SName
import sttp.tapir.Schema.annotations.customise
import sttp.tapir.{FieldName, Schema, SchemaType}

import nl.amony.modules.auth.api.UserId
import nl.amony.modules.resources.api.*
import nl.amony.modules.resources.api.{ImageProperties, ResourceInfo, ResourceMeta, VideoProperties}

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
  timeCreated: Option[Long],
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

  val resourceHeight = resource.contentMeta match
    case Some(ResourceMeta(_, _, VideoProperties(_, h, _, _, _))) => h
    case Some(ResourceMeta(_, _, ImageProperties(_, h, _)))       => h
    case _                                                        => 0

  val resolutions: List[Int] = (resourceHeight :: List(352)).sorted

  val durationInMillis = resource.contentMeta.map(_.properties) match {
    case Some(m: VideoProperties) => m.durationInMillis
    case _                        => 0
  }

  val thumbnailTimestamp: Int = resource.thumbnailTimestamp.getOrElse(durationInMillis / 3)

  val urls = {

    val tsPart = if thumbnailTimestamp != 0 then s"_$thumbnailTimestamp" else ""

    ResourceUrlsDto(
      originalResourceUrl  = s"/api/resources/${resource.bucketId}/${resource.resourceId}/content",
      thumbnailUrl         = s"/api/resources/${resource.bucketId}/${resource.resourceId}/thumb${tsPart}_${resolutions.min}p.webp",
      previewThumbnailsUrl = Some(s"/api/resources/${resource.bucketId}/${resource.resourceId}/timeline.vtt")
    )
  }

  val filename = {
    val slashIdx = resource.path.lastIndexOf('/')
    val dotIdx   = resource.path.lastIndexOf('.')

    val startIdx = if slashIdx >= 0 then slashIdx + 1 else 0
    val endIdx   = if dotIdx >= 0 then dotIdx else resource.path.length

    resource.path.substring(startIdx, endIdx)
  }

  val contentMeta: ResourceMetaDto = resource.contentMeta.map(_.properties) match {
    case Some(ImageProperties(width, height, _)) => ResourceMetaDto(width = width, height = height, duration = 0, fps = 0, codec = None)

    case Some(VideoProperties(width, height, fps, duration, codec)) =>
      ResourceMetaDto(width = width, height = height, duration = duration, fps = fps, codec = codec)

    case None => ResourceMetaDto(width = 0, height = 0, duration = 0, fps = 0, codec = None)
  }

  // a clip that starts at the thumbnail timestamp and lasts for 3 seconds
  val thumbnailClip = {
    val start = thumbnailTimestamp
    val end   = Math.min(contentMeta.duration, thumbnailTimestamp + 3000)
    val urls  = resolutions.map(height => s"/api/resources/${resource.bucketId}/${resource.resourceId}/clip_$start-${end}_${height}p.mp4")

    ClipDto(resourceId = resource.resourceId, start = start, end = end, urls = urls, description = None, tags = List.empty)
  }

  val contentMetaSource = resource.contentMeta
    .map(s => ResourceToolMetaDto(s.toolName, io.circe.parser.parse(s.toolData).getOrElse(Json.fromString(s.toolData))))

  ResourceDto(
    bucketId           = resource.bucketId,
    resourceId         = resource.resourceId,
    hash               = resource.hash,
    sizeInBytes        = resource.size,
    path               = resource.path,
    timeAdded          = resource.timeAdded.getOrElse(0L),
    timeCreated        = resource.timeCreated,
    timeLastModified   = resource.timeLastModified,
    userId             = resource.userId,
    title              = resource.title,
    description        = resource.description,
    tags               = resource.tags.toList,
    contentType        = resource.contentType.getOrElse("application/octet-stream"),
    contentMeta        = contentMeta,
    urls               = urls,
    thumbnailTimestamp = resource.thumbnailTimestamp,
    clips              = List(thumbnailClip)
  )
}

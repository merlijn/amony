package nl.amony.service.resources.web.dto

import io.circe.*
import nl.amony.service.resources.domain.*
import sttp.tapir.Schema.SName
import sttp.tapir.{FieldName, Schema, SchemaType}
import sttp.tapir.Schema.annotations.customise

def required[T](s: Schema[T]) = s.copy(isOptional = false)

case class BucketDto(
  bucketId: String,
  name: String,
  `type`: String, 
) derives Codec, sttp.tapir.Schema

case class ThumbnailTimestampDto(
   timestampInMillis: Int
) derives Codec, sttp.tapir.Schema

case class UserMetaDto(
  title: Option[String],
  description: Option[String],
  @customise(required)
  tags: List[String]
) derives Codec, sttp.tapir.Schema

case class BulkTagsUpdateDto(
  ids: List[String],
  tagsToRemove: List[String],
  tagsToAdd: List[String]
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

case class ResourceToolMetaDto(
  toolName: String,
  toolData: Json,
) derives Codec

object ResourceToolMetaDto {
  given schemaForCirceJsonAny: Schema[Json] = Schema.any[Json]
  given Schema[ResourceToolMetaDto] = Schema.derived[ResourceToolMetaDto]
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
  contentMetaSource: Option[ResourceToolMetaDto],
  urls: ResourceUrlsDto,
  thumbnailTimestamp: Option[Int],
  @customise(required)
  clips: List[ClipDto],
) derives Codec, sttp.tapir.Schema {

  def toDomain(): ResourceInfo = {
    
    ResourceInfo(
      bucketId = bucketId,
      resourceId = resourceId,
      userId = userId,
      path = path,
      hash = hash,
      size = sizeInBytes,
      contentType = Some(contentType),
      contentMeta = ResourceMeta.Empty, // Can be re-created from the source
      contentMetaSource = contentMetaSource.map(
        s => ResourceMetaSource(s.toolName, s.toolData.noSpaces)
      ),
      tags = tags.toSet,
      timeAdded = Some(timeAdded),
      title = title,
      description = description,
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

  val resourceHeight =
    resource.contentMeta match
      case VideoMeta(_, h, _, _, _, _) => h
      case ImageMeta(_, h, _) => h
      case _ => 0

  val resolutions: List[Int] = (resourceHeight :: List(352)).sorted

  val durationInMillis =
    resource.contentMeta match {
      case m: VideoMeta => m.durationInMillis
      case _ => 0
    }

  val thumbnailTimestamp: Int = resource.thumbnailTimestamp.getOrElse(durationInMillis / 3)

  val urls = {

    val tsPart = if (thumbnailTimestamp != 0) s"_${thumbnailTimestamp}" else ""

    ResourceUrlsDto(
      originalResourceUrl  = s"/api/resources/${resource.bucketId}/${resource.resourceId}/content",
      thumbnailUrl         = s"/api/resources/${resource.bucketId}/${resource.resourceId}/thumb${tsPart}_${resolutions.min}p.webp",
      previewThumbnailsUrl = Some(s"/api/resources/${resource.bucketId}/${resource.resourceId}/timeline.vtt")
    )
  }

  val filename = {
    val slashIdx = resource.path.lastIndexOf('/')
    val dotIdx = resource.path.lastIndexOf('.')

    val startIdx = if (slashIdx >= 0) slashIdx + 1 else 0
    val endIdx = if (dotIdx >= 0) dotIdx else resource.path.length

    resource.path.substring(startIdx, endIdx)
  }
  
  val contentMeta: ResourceMetaDto = resource.contentMeta match {
    case ImageMeta(width, height, _) =>
      ResourceMetaDto(
        width = width,
        height = height,
        duration = 0,
        fps = 0,
        codec = None,
      )

    case VideoMeta(width, height, fps, duration, codec, _) =>
      ResourceMetaDto(
        width = width,
        height = height,
        duration = duration,
        fps = fps,
        codec = codec,
      )

    case ResourceMeta.Empty =>
      ResourceMetaDto(
        width = 0,
        height = 0,
        duration = 0,
        fps = 0,
        codec = None,
      )
  }
  
  // a clip that starts at the thumbnail timestamp and lasts for 3 seconds
  val thumbnailClip = {
    val start = thumbnailTimestamp
    val end   = Math.min(contentMeta.duration, thumbnailTimestamp + 3000)
    val urls  = resolutions.map(height => s"/api/resources/${resource.bucketId}/${resource.resourceId}/clip_${start}-${end}_${height}p.mp4")

    ClipDto(
      resourceId = resource.resourceId,
      start = start,
      end = end,
      urls = urls,
      description = None,
      tags = List.empty
    )
  }

  val contentMetaSource = resource.contentMetaSource.map(
    s => ResourceToolMetaDto(s.toolName, io.circe.parser.parse(s.toolData).getOrElse(Json.fromString(s.toolData)))
  )

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
    contentMetaSource  = contentMetaSource,
    urls               = urls,
    thumbnailTimestamp = resource.thumbnailTimestamp,
    clips              = List(thumbnailClip)
  )
}


package nl.amony.service.resources.web.dto

import io.circe.*
import nl.amony.service.resources.api.*
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
  timeCreated: Long,
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


def fromDto(dto: ResourceDto): ResourceInfo = {
  
  val contentMetaSource = dto.contentMetaSource.map(
      s => ResourceMetaSource(s.toolName, s.toolData.noSpaces)
  )
  
  ResourceInfo(
    bucketId = dto.bucketId,
    resourceId = dto.resourceId,
    userId = dto.userId,
    path = dto.path,
    hash = dto.hash,
    size = dto.sizeInBytes,
    contentType = Some(dto.contentType),
    contentMeta = ResourceMeta.Empty, // Can be re-created from the source
    contentMetaSource = contentMetaSource,
    tags = dto.tags.toSet,
    creationTime = Some(dto.timeCreated),
    title = dto.title,
    description = dto.description,
    thumbnailTimestamp = dto.thumbnailTimestamp
  )
}

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

  // hard coded for now
  val clips = List(ClipDto(resource.resourceId, thumbnailTimestamp, Math.min(contentMeta.duration, thumbnailTimestamp + 3000), List.empty, None, List.empty))

  val contentMetaSource = resource.contentMetaSource.map(
    s => ResourceToolMetaDto(s.toolName, io.circe.parser.parse(s.toolData).getOrElse(Json.fromString(s.toolData)))
  )

  ResourceDto(
    bucketId    = resource.bucketId,
    resourceId  = resource.resourceId,
    hash        = resource.hash,
    sizeInBytes = resource.size,
    path        = resource.path,
    timeCreated = resource.creationTime.getOrElse(0L),
    userId      = resource.userId,
    title       = resource.title.orElse(Some(filename)),
    description = resource.description,
    tags        = resource.tags.toList,
    contentType = resource.contentType.getOrElse("application/octet-stream"),
    contentMeta = contentMeta,
    contentMetaSource = contentMetaSource,
    urls = urls,
    thumbnailTimestamp = resource.thumbnailTimestamp,
    clips = {
      clips.map { f =>

        val urls = resolutions.map(height =>
          s"/api/resources/${resource.bucketId}/${resource.resourceId}/clip_${f.start}-${f.end}_${height}p.mp4"
        )

        ClipDto(
          resourceId = resource.resourceId,
          start = f.start,
          end = f.end,
          urls = urls,
          description = f.description,
          tags = f.tags
        )
      }
    }
  )
}


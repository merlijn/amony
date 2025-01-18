package nl.amony.service.resources.web.dto

import io.circe.*
import nl.amony.service.resources.api.*
import sttp.tapir.Schema
import sttp.tapir.Schema.annotations.customise

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

case class ResourceToolMetaDto(
  toolName: String,
  toolData: String,
) derives Codec, sttp.tapir.Schema

case class ResourceDto(
  bucketId: String,
  resourceId: String,
  hash: Option[String],
  sizeInBytes: Long,
  path: String,
  uploader: String,
  uploadTimestamp: Long,
  userMeta: UserMetaDto,
  contentType: String,
  contentMeta: ResourceMetaDto,
  contentMetaSource: Option[ResourceToolMetaDto],
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
      case _ => 0L
    }

  val thumbnailTimestamp: Long = resource.thumbnailTimestamp.getOrElse(durationInMillis / 3)

  val urls = {

    val tsPart = if (thumbnailTimestamp != 0) s"_${thumbnailTimestamp}" else ""

    ResourceUrlsDto(
      originalResourceUrl  = s"/api/resources/${resource.bucketId}/${resource.hash}/content",
      thumbnailUrl         = s"/api/resources/${resource.bucketId}/${resource.hash}/thumb${tsPart}_${resolutions.min}p.webp",
      previewThumbnailsUrl = Some(s"/api/resources/${resource.bucketId}/${resource.hash}/timeline.vtt")
    )
  }

  val filename = {
    val slashIdx = resource.path.lastIndexOf('/')
    val dotIdx = resource.path.lastIndexOf('.')

    val startIdx = if (slashIdx >= 0) slashIdx + 1 else 0
    val endIdx = if (dotIdx >= 0) dotIdx else resource.path.length

    resource.path.substring(startIdx, endIdx)
  }

  val userMeta = UserMetaDto(
    title       = resource.title.orElse(Some(filename)),
    description = resource.description,
    tags        = resource.tags.toList
  )

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
  val highlights = List(ClipDto(resource.hash, thumbnailTimestamp, Math.min(contentMeta.duration, thumbnailTimestamp + 3000), List.empty, None, List.empty))

  ResourceDto(
    bucketId = resource.bucketId,
    resourceId = resource.hash,
    hash = Some(resource.hash),
    sizeInBytes = resource.size,
    path = resource.path,
    uploader = "0",
    uploadTimestamp = resource.getCreationTime,
    urls = urls,
    userMeta = userMeta,
    contentType = resource.contentType.getOrElse("unknown"),
    contentMeta = contentMeta,
    contentMetaSource = resource.contentMetaSource.map(s => ResourceToolMetaDto(s.toolName, s.toolData)),
    thumbnailTimestamp = resource.thumbnailTimestamp,
    clips = {
      highlights.map { f =>

        val urls = resolutions.map(height =>
          s"/api/resources/${resource.bucketId}/${resource.hash}/clip_${f.start}-${f.end}_${height}p.mp4"
        )

        ClipDto(
          resourceId = resource.hash,
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


package nl.amony.modules.resources.http

import java.util.UUID

import io.circe.*
import sttp.tapir.Schema.SName
import sttp.tapir.Schema.annotations.customise
import sttp.tapir.{FieldName, Schema, SchemaType}

import nl.amony.lib.tapir.required
import nl.amony.modules.auth.api.UserId
import nl.amony.modules.resources.api.*
import nl.amony.modules.resources.api.{ImageProperties, ResourceInfo, VideoProperties}

case class BucketDto(bucketId: String, name: String, `type`: String) derives Codec, sttp.tapir.Schema

case class ThumbnailTimestampDto(timestampInMillis: Int) derives Codec, sttp.tapir.Schema

case class UserMetaDto(
  title: Option[String],
  description: Option[String],
  @customise(required)
  tags: List[String]
) derives Codec, sttp.tapir.Schema

case class BulkTagsUpdateDto(ids: List[String], tagsToRemove: List[String], tagsToAdd: List[String]) derives Codec, sttp.tapir.Schema

case class ResourceMetaDto(width: Int, height: Int, fps: Float, duration: Long, codec: Option[String], streamable: Option[Boolean] = None)
    derives Codec, sttp.tapir.Schema

case class ResourceToolMetaDto(toolName: String, toolData: Json) derives Codec

object ResourceToolMetaDto {
  given schemaForCirceJsonAny: Schema[Json] = Schema.any[Json]
  given Schema[ResourceToolMetaDto]         = Schema.derived[ResourceToolMetaDto]
}

object ResourceDto:
  given schemaForCirceJsonAny: Schema[Option[Json]] = Schema.any[Option[Json]]
  given Schema[ResourceDto]                         = Schema.derived[ResourceDto]

case class ResourceDto(
  bucketId: String,
  resourceId: String,
  partialHash: Option[String],
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
  thumbnailTimestamp: Option[Int],
  @customise(required)
  clips: List[ClipDto],
  fullMeta: Option[Json] = None
) derives Codec {

  def toDomain(): ResourceInfo = {

    ResourceInfo(
      bucketId           = BucketId(bucketId),
      resourceId         = ResourceId(resourceId),
      userId             = UserId(userId),
      path               = path,
      partialHash        = partialHash,
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
  bucketId: String,
  resourceId: String,
  start: Long,
  end: Long,
  description: Option[String],
  @customise(required)
  tags: List[String]
) derives Codec, sttp.tapir.Schema

case class CollectionDto(
  id: UUID,
  parentId: Option[UUID],
  name: String,
  description: Option[String],
  @customise(required)
  tags: List[String]
) derives Codec, sttp.tapir.Schema

case class CreateCollectionDto(
  name: String,
  parentId: Option[UUID],
  description: Option[String],
  tags: List[String]
) derives Codec, sttp.tapir.Schema

def toDto(resource: ResourceInfo): ResourceDto = {

  val durationInMillis = resource.basicContentProperties match {
    case Some(m: VideoProperties) => m.durationInMillis
    case _                        => 0
  }

  // Thumbnail timestamp: use saved value, fall back to 1/3 of duration
  val thumbnailTimestamp: Int = resource.thumbnailTimestamp.getOrElse(durationInMillis / 3)

  // `None` (unknown / not a video) is reported as no value, so clients only flag a container they know is not streamable.
  val streamable: Option[Boolean] = resource.streamable

  val contentMeta: ResourceMetaDto = resource.basicContentProperties match {
    case Some(ImageProperties(width, height, _)) => ResourceMetaDto(width = width, height = height, duration = 0, fps = 0, codec = None)

    case Some(VideoProperties(width, height, fps, duration, codec)) =>
      ResourceMetaDto(width = width, height = height, duration = duration, fps = fps, codec = codec, streamable = streamable)

    case None => ResourceMetaDto(width = 0, height = 0, duration = 0, fps = 0, codec = None)
  }

  // A preview clip spanning the resource's preview range; the client builds the clip URL from these timestamps.
  val thumbnailClip = resource.basicContentProperties match {
    case Some(_: VideoProperties) =>
      val (start, end) = VideoFragment.previewRange(resource.thumbnailTimestamp, durationInMillis)
      Some(ClipDto(
        bucketId    = resource.bucketId,
        resourceId  = resource.resourceId,
        start       = start,
        end         = end,
        description = None,
        tags        = List.empty
      ))
    case _                        =>
      None
  }

  val fullMeta: Option[Json] =
    resource.contentMeta.flatMap { meta =>
      io.circe.parser.parse(meta.toolData).toOption
    }

  ResourceDto(
    bucketId           = resource.bucketId,
    resourceId         = resource.resourceId,
    partialHash        = resource.partialHash,
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
    thumbnailTimestamp = Some(thumbnailTimestamp),
    clips              = thumbnailClip.toList,
    fullMeta           = fullMeta
  )
}

def toDto(collection: Collection): CollectionDto =
  CollectionDto(
    id          = collection.id.value,
    parentId    = collection.parentId.map(_.value),
    name        = collection.name,
    description = collection.description,
    tags        = collection.tags.toList
  )

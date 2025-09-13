package nl.amony.service.resources.database

import nl.amony.service.resources.api.{ResourceInfo, ResourceMeta, ResourceMetaSource}
import skunk.Codec
import skunk.codec.all.{int4, timestamptz, varchar}
import skunk.implicits.sql

import java.time.{Instant, ZoneOffset}

val instantCodec: Codec[Instant] =
  timestamptz.imap(_.toInstant)(_.atOffset(ZoneOffset.UTC))

case class ResourceRow(
  bucket_id: String,
  resource_id: String,
  user_id: String,
  hash: Option[String],
  size: Long,
  content_type: Option[String],
  content_meta_tool_name: Option[String],
  content_meta_tool_data: Option[String],
  fs_path: String,
  time_added: Instant,
  time_created: Option[Instant],
  time_last_modified: Option[Instant],
  title: Option[String],
  description: Option[String],
  thumbnail_timestamp: Option[Int] = None) derives io.circe.Codec {

  def toResource(tagLabels: Set[String]): ResourceInfo = {
    ResourceInfo(
      bucketId = bucket_id,
      resourceId = resource_id,
      userId = user_id,
      path = fs_path,
      hash = hash,
      size = size,
      contentType = content_type,
      contentMetaSource = content_meta_tool_name.map(name => ResourceMetaSource(name, content_meta_tool_data.getOrElse(""))),
      contentMeta = ResourceMeta.Empty,
      tags = tagLabels,
      timeAdded = Some(time_added.toEpochMilli),
      timeCreated = time_created.map(_.toEpochMilli),
      timeLastModified = time_last_modified.map(_.toEpochMilli),
      title = title,
      description = description,
      thumbnailTimestamp = thumbnail_timestamp
    )
  }
}


object ResourceRow {

  val columns = sql"r.bucket_id, r.resource_id, r.user_id, r.hash, r.size, r.content_type, r.content_meta_tool_name, r.content_meta_tool_data, r.fs_path, r.time_added, r.time_last_modified, r.title, r.description, r.thumbnail_timestamp"

  def fromResource(resource: ResourceInfo): ResourceRow = ResourceRow(
    bucket_id = resource.bucketId,
    resource_id = resource.resourceId,
    user_id = resource.userId,
    hash = resource.hash,
    size = resource.size,
    content_type = resource.contentType,
    content_meta_tool_name = resource.contentMetaSource.map(_.toolName),
    content_meta_tool_data = resource.contentMetaSource.map(_.toolData),
    fs_path = resource.path,
    time_added = Instant.ofEpochMilli(resource.timeAdded.getOrElse(0L)),
    time_created = None,
    time_last_modified = resource.timeLastModified.map(Instant.ofEpochMilli),
    title = resource.title,
    description = resource.description,
    thumbnail_timestamp = resource.thumbnailTimestamp
  )
}

case class ResourceTagsRow(
  bucket_id: String,
  resource_id: String,
  tag_id: Int
)

object ResourceTagsRow:
  val codec: Codec[ResourceTagsRow] = (varchar(64) *: varchar(64) *: int4).to[ResourceTagsRow]

case class TagRow(id: Int, label: String)

object TagRow:
  val codec: Codec[TagRow] = (int4 *: varchar(64)).imap((TagRow.apply _).tupled)(tag => (tag.id, tag.label))  


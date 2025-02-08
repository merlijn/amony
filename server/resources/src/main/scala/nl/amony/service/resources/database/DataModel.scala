package nl.amony.service.resources.database

import nl.amony.service.resources.api.{ResourceInfo, ResourceMeta, ResourceMetaSource}
import skunk.Codec
import skunk.codec.all.{int4, varchar}

case class ResourceRow(
  bucket_id: String,
  resource_id: String,
  user_id: String,
  relative_path: String,
  hash: String,
  size: Long,
  content_type: Option[String],
  content_meta_tool_name: Option[String],
  content_meta_tool_data: Option[String],
  creation_time: Option[Long],
  last_modified_time: Option[Long],
  title: Option[String],
  description: Option[String],
  thumbnail_timestamp: Option[Long] = None) derives io.circe.Codec {

  def toResource(tagLabels: Set[String]): ResourceInfo = {
    ResourceInfo(
      bucketId = bucket_id,
      resourceId = resource_id,
      userId = user_id,
      path = relative_path,
      hash = Some(hash),
      size = size,
      contentType = content_type,
      contentMetaSource = content_meta_tool_name.map(name => ResourceMetaSource(name, content_meta_tool_data.getOrElse(""))),
      contentMeta = ResourceMeta.Empty,
      tags = tagLabels,
      creationTime = creation_time,
      lastModifiedTime = last_modified_time,
      title = title,
      description = description,
      thumbnailTimestamp = thumbnail_timestamp
    )
  }
}

object ResourceRow {

  def fromResource(resource: ResourceInfo): ResourceRow = ResourceRow(
    bucket_id = resource.bucketId,
    resource_id = resource.resourceId,
    user_id = resource.userId,
    relative_path = resource.path,
    hash = resource.hash.get,
    size = resource.size,
    content_type = resource.contentType,
    content_meta_tool_name = resource.contentMetaSource.map(_.toolName),
    content_meta_tool_data = resource.contentMetaSource.map(_.toolData),
    creation_time = resource.creationTime,
    last_modified_time = resource.lastModifiedTime,
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
  val codec: Codec[ResourceTagsRow] = (varchar(128) *: varchar(128) *: int4).to[ResourceTagsRow]

case class TagRow(
  id: Int,
  label: String
)

val tagRow: Codec[TagRow] =
  (int4 *: varchar(64)).imap((TagRow.apply _).tupled)(tag => (tag.id, tag.label))  


package nl.amony.modules.resources.dal

import java.time.{Instant, ZoneOffset}
import java.util.UUID

import skunk.Codec
import skunk.codec.all.{int4, timestamptz, uuid, varchar}
import skunk.implicits.sql

import nl.amony.modules.auth.api.UserId
import nl.amony.modules.resources.api.{BucketId, Collection, CollectionId, ResourceId, ResourceInfo, ResourceMeta, Tag}

val instantCodec: Codec[Instant] = timestamptz.imap(_.toInstant)(_.atOffset(ZoneOffset.UTC))

case class ResourceRow(
  bucket_id: String,
  resource_id: String,
  user_id: String,
  partial_hash: Option[String],
  file_key: Option[String]         = None,
  size: Long,
  content_type: Option[String],
  content_meta_tool_name: Option[String],
  content_meta_tool_data: Option[String],
  fs_path: String,
  time_added: Instant,
  time_created: Option[Instant], // TODO remove this field
  time_last_modified: Option[Instant],
  title: Option[String],
  description: Option[String],
  thumbnail_timestamp: Option[Int] = None,
  streamable: Option[Boolean]      = None
) derives io.circe.Codec {

  def toResource(tagLabels: Set[String], systemTagLabels: Set[String] = Set.empty): ResourceInfo = {
    ResourceInfo(
      bucketId           = BucketId(bucket_id),
      resourceId         = ResourceId(resource_id),
      userId             = UserId(user_id),
      path               = fs_path,
      partialHash        = partial_hash,
      fileKey            = file_key,
      size               = size,
      contentType        = content_type,
      contentMeta        = content_meta_tool_name.flatMap(name => ResourceMeta.recover(name, content_meta_tool_data.getOrElse(""))),
      tags               = tagLabels.map(Tag.user) ++ systemTagLabels.map(Tag.system),
      timeAdded          = Some(time_added.toEpochMilli),
      timeLastModified   = time_last_modified.map(_.toEpochMilli),
      title              = title,
      description        = description,
      thumbnailTimestamp = thumbnail_timestamp,
      streamable         = streamable
    )
  }
}

object ResourceRow {

  val columns =
    sql"r.bucket_id, r.resource_id, r.user_id, r.partial_hash, r.file_key, r.size, r.content_type, r.content_meta_tool_name, r.content_meta_tool_data, r.fs_path, r.time_added, r.time_last_modified, r.title, r.description, r.thumbnail_timestamp, r.streamable"

  def fromResource(resource: ResourceInfo): ResourceRow = ResourceRow(
    bucket_id              = resource.bucketId,
    resource_id            = resource.resourceId,
    user_id                = resource.userId,
    partial_hash           = resource.partialHash,
    file_key               = resource.fileKey,
    size                   = resource.size,
    content_type           = resource.contentType,
    content_meta_tool_name = resource.contentMeta.map(_.toolName),
    content_meta_tool_data = resource.contentMeta.map(_.toolData),
    fs_path                = resource.path,
    time_added             = Instant.ofEpochMilli(resource.timeAdded.getOrElse(0L)),
    time_created           = None,
    time_last_modified     = resource.timeLastModified.map(Instant.ofEpochMilli),
    title                  = resource.title,
    description            = resource.description,
    thumbnail_timestamp    = resource.thumbnailTimestamp,
    streamable             = resource.streamable
  )
}

case class CollectionRow(
  id: UUID,
  parent_id: Option[UUID],
  user_id: String,
  name: String,
  description: Option[String]
) derives io.circe.Codec {

  def toCollection(tagLabels: Set[String]): Collection =
    Collection(
      id          = CollectionId(id),
      parentId    = parent_id.map(CollectionId(_)),
      userId      = UserId(user_id),
      name        = name,
      description = description,
      tags        = tagLabels
    )
}

object CollectionRow {

  val columns = sql"c.id, c.parent_id, c.user_id, c.name, c.description"

  val codec: Codec[CollectionRow] = (uuid *: uuid.opt *: varchar(64) *: varchar(64) *: varchar.opt).to[CollectionRow]

  def fromCollection(collection: Collection): CollectionRow =
    CollectionRow(
      id          = collection.id.value,
      parent_id   = collection.parentId.map(_.value),
      user_id     = collection.userId,
      name        = collection.name,
      description = collection.description
    )
}

case class ResourceTagsRow(bucket_id: String, resource_id: String, tag_id: Int)

object ResourceTagsRow:
  val codec: Codec[ResourceTagsRow] = (varchar(64) *: varchar(64) *: int4).to[ResourceTagsRow]

case class CollectionTagsRow(collection_id: UUID, tag_id: Int)

object CollectionTagsRow:
  val codec: Codec[CollectionTagsRow] = (uuid *: int4).to[CollectionTagsRow]

case class CollectionResourcesRow(collection_id: UUID, bucket_id: String, resource_id: String)

object CollectionResourcesRow:
  val codec: Codec[CollectionResourcesRow] = (uuid *: varchar(64) *: varchar(64)).to[CollectionResourcesRow]

case class TagRow(id: Int, owner: String, label: String)

object TagRow:
  val codec: Codec[TagRow] = (int4 *: varchar(64) *: varchar(64)).imap(TagRow.apply.tupled)(tag => (tag.id, tag.owner, tag.label))

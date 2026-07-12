package nl.amony.modules.resources.dal

import java.time.{Instant, ZoneOffset}
import java.util.UUID

import skunk.Codec
import skunk.codec.all.{int4, timestamptz, uuid, varchar}
import skunk.implicits.sql

import nl.amony.modules.auth.api.UserId
import nl.amony.modules.resources.api.{Collection, CollectionId, ResourceId, ResourceInfo, ResourceMeta}

val instantCodec: Codec[Instant] = timestamptz.imap(_.toInstant)(_.atOffset(ZoneOffset.UTC))

case class ResourceRow(
  bucket_id: String,
  resource_id: String,
  user_id: String,
  partial_hash: Option[String],
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
  thumbnail_timestamp: Option[Int] = None
) derives io.circe.Codec {

  def toResource(tagLabels: Set[String]): ResourceInfo = {
    ResourceInfo(
      bucketId           = bucket_id,
      resourceId         = ResourceId(resource_id),
      userId             = UserId(user_id),
      path               = fs_path,
      partialHash        = partial_hash,
      size               = size,
      contentType        = content_type,
      contentMeta        = content_meta_tool_name.flatMap(name => ResourceMeta.recover(name, content_meta_tool_data.getOrElse(""))),
      tags               = tagLabels,
      timeAdded          = Some(time_added.toEpochMilli),
      timeLastModified   = time_last_modified.map(_.toEpochMilli),
      title              = title,
      description        = description,
      thumbnailTimestamp = thumbnail_timestamp
    )
  }
}

object ResourceRow {

  val columns =
    sql"r.bucket_id, r.resource_id, r.user_id, r.partial_hash, r.size, r.content_type, r.content_meta_tool_name, r.content_meta_tool_data, r.fs_path, r.time_added, r.time_last_modified, r.title, r.description, r.thumbnail_timestamp"

  def fromResource(resource: ResourceInfo): ResourceRow = ResourceRow(
    bucket_id              = resource.bucketId,
    resource_id            = resource.resourceId,
    user_id                = resource.userId,
    partial_hash           = resource.partialHash,
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
    thumbnail_timestamp    = resource.thumbnailTimestamp
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

case class TagRow(id: Int, label: String)

object TagRow:
  val codec: Codec[TagRow] = (int4 *: varchar(64)).imap(TagRow.apply.tupled)(tag => (tag.id, tag.label))

package nl.amony.service.resources.database

import nl.amony.service.resources.api.{ResourceInfo, ResourceMeta, ResourceMetaMessage, ResourceMetaSource}
import scribe.Logging
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile

case class ResourceRow(
   bucketId: String,
   relativePath: String,
   hash: String,
   size: Long,
   contentType: Option[String],
   contentMetaToolName: Option[String],
   contentMetaToolData: Option[String],
   creationTime: Option[Long],
   lastModifiedTime: Option[Long],
   title: Option[String],
   description: Option[String],
   thumbnailTimestamp: Option[Long] = None) {

  def toResource(tagLabels: Set[String]): ResourceInfo = {
    ResourceInfo(
      bucketId = bucketId,
      resourceId = hash,
      userId = "0",
      path = relativePath,
      hash = Some(hash),
      size = size,
      contentType = contentType,
      contentMetaSource = contentMetaToolName.map(name => ResourceMetaSource(name, contentMetaToolData.getOrElse(""))),
      contentMeta = ResourceMeta.Empty,
      tags = tagLabels,
      creationTime = creationTime,
      lastModifiedTime = lastModifiedTime,
      title = title,
      description = description,
      thumbnailTimestamp = thumbnailTimestamp
    )
  }
}

object ResourceRow  {

  def fromResource(resource: ResourceInfo): ResourceRow = ResourceRow(
    bucketId = resource.bucketId,
    relativePath = resource.path,
    hash = resource.hash.get,
    size = resource.size,
    contentType = resource.contentType,
    contentMetaToolName = resource.contentMetaSource.map(_.toolName),
    contentMetaToolData = resource.contentMetaSource.map(_.toolData),
    creationTime = resource.creationTime,
    lastModifiedTime = resource.lastModifiedTime,
    title = resource.title,
    description = resource.description,
    thumbnailTimestamp = resource.thumbnailTimestamp
  )
}

class ResourceTable[P <: JdbcProfile](val dbConfig: DatabaseConfig[P]) extends Logging {

  import dbConfig.profile.api.*

  class ResourceSchema(ttag: slick.lifted.Tag) extends Table[ResourceRow](ttag, "files") {

    def bucketId = column[String]("bucket_id")
    def relativePath = column[String]("relative_path")
    def contentType = column[Option[String]]("content_type")
    def contentMetaToolName = column[Option[String]]("content_meta_tool_name")
    def contentMetaToolData = column[Option[String]]("content_meta_tool_data")
    def resourceId = column[String]("resource_id")
    def size = column[Long]("size")
    def creationTime = column[Option[Long]]("creation_time")
    // we only store this to later check if the file has not been modified
    def lastModifiedTime = column[Option[Long]]("last_modified_time")
    def title = column[Option[String]]("title")
    def description = column[Option[String]]("description")
    def thumbnailTimestamp = column[Option[Long]]("thumbnail_timestamp")

    def bucketIdx = index("bucket_id_idx", bucketId)
    def hashIdx = index("hash_idx", resourceId)
    def pk = primaryKey("resources_pk", (bucketId, resourceId))

    def * = (bucketId, relativePath, resourceId, size, contentType, contentMetaToolName, contentMetaToolData, creationTime, lastModifiedTime, title, description, thumbnailTimestamp) <>
      ((ResourceRow.apply _).tupled, ResourceRow.unapply)
  }

  val table = TableQuery[ResourceSchema]

  def getById(bucketId: String, resourceId: String): Query[ResourceSchema, ResourceRow, Seq] =
    table
      .filter(_.bucketId === bucketId)
      .filter(_.resourceId === resourceId)

  def getByPath(bucketId: String, path: String) =
    table
      .filter(_.bucketId === bucketId)
      .filter(_.relativePath === path)

  def insert(resource: ResourceInfo) =
    table += ResourceRow.fromResource(resource)

  def update(row: ResourceRow) =
    getById(row.bucketId, row.hash).update(row)

  def update(resource: ResourceInfo) =
    getById(resource.bucketId, resource.resourceId).update(ResourceRow.fromResource(resource))

  def upsert(resource: ResourceInfo) =
    table.insertOrUpdate(ResourceRow.fromResource(resource))

  def allForBucket(bucketId: String) =
    table.filter(_.bucketId === bucketId)
}

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

  def toResource(tags: Seq[String]): ResourceInfo = {
    ResourceInfo(
      bucketId = bucketId,
      path = relativePath,
      hash = hash,
      size = size,
      contentType = contentType,
      contentMetaSource = contentMetaToolName.map(name => ResourceMetaSource(name, contentMetaToolData.getOrElse(""))),
      contentMeta = ResourceMeta.Empty,
      tags = tags,
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
    hash = resource.hash,
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

  class LocalFilesSchema(ttag: slick.lifted.Tag) extends Table[ResourceRow](ttag, "files") {

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

  val innerTable = TableQuery[LocalFilesSchema]

  def getByHash(bucketId: String, hash: String): Query[LocalFilesSchema, ResourceRow, Seq] =
    innerTable
      .filter(_.bucketId === bucketId)
      .filter(_.resourceId === hash)

  def getByPath(bucketId: String, path: String) =
    innerTable
      .filter(_.bucketId === bucketId)
      .filter(_.relativePath === path)

  def insert(resource: ResourceInfo) =
    innerTable += ResourceRow.fromResource(resource)

  def update(row: ResourceRow) =
    getByHash(row.bucketId, row.hash).update(row)

  def update(resource: ResourceInfo) =
    getByHash(resource.bucketId, resource.hash).update(ResourceRow.fromResource(resource))

  def insertOrUpdate(resource: ResourceInfo) =
    // ! The insertOrUpdate operation does not work in combination with a byte array field and hsqldb
    innerTable.insertOrUpdate(ResourceRow.fromResource(resource))

  def allForBucket(bucketId: String) =
    innerTable.filter(_.bucketId === bucketId)
}

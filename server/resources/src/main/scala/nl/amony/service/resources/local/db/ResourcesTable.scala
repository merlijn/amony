package nl.amony.service.resources.local.db

import nl.amony.service.resources.api.{ResourceInfo, ResourceMeta, ResourceMetaMessage}
import scribe.Logging
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile

case class ResourceRow(
   bucketId: String,
   parentId: Option[String],
   relativePath: String,
   hash: String,
   size: Long,
   contentType: Option[String],
   contentMeta: Option[Array[Byte]],
   operationData: Option[Array[Byte]],
   creationTime: Option[Long],
   lastModifiedTime: Option[Long],
   title: Option[String],
   description: Option[String]) {

  def toResource(tags: Seq[String]): ResourceInfo = {
    ResourceInfo(
      bucketId = bucketId,
      parentId = parentId,
      path = relativePath,
      hash = hash,
      size = size,
      contentType = contentType,
      contentMeta = ResourceRow.decodeMeta(contentMeta),
      tags = tags,
      creationTime = creationTime,
      lastModifiedTime = lastModifiedTime,
      title = title,
      description = description
    )
  }
}

object ResourceRow  {

  def decodeMeta(maybeBytes: Option[Array[Byte]]): ResourceMeta = maybeBytes match {
    case None => ResourceMeta.Empty
    case Some(bytes) =>
      val msg = ResourceMetaMessage.parseFrom(bytes)
      ResourceMeta.ResourceMetaTypeMapper.toCustom(msg)
  }

  def encodeMeta(meta: ResourceMeta): Option[Array[Byte]] = {

    Option.when(!meta.isEmpty) {
      val bytes = ResourceMeta.ResourceMetaTypeMapper.toBase(meta).toByteArray
      bytes
    }
  }

  def fromResource(resource: ResourceInfo): ResourceRow = ResourceRow(
    bucketId = resource.bucketId,
    parentId = resource.parentId,
    relativePath = resource.path,
    hash = resource.hash,
    size = resource.size,
    contentType = resource.contentType,
    contentMeta = encodeMeta(resource.contentMeta),
    operationData = None,
    creationTime = resource.creationTime,
    lastModifiedTime = resource.lastModifiedTime,
    title = resource.title,
    description = resource.description
  )
}

class ResourcesTable[P <: JdbcProfile](val dbConfig: DatabaseConfig[P]) extends Logging {

  import dbConfig.profile.api._

  class LocalFilesSchema(ttag: slick.lifted.Tag) extends Table[ResourceRow](ttag, "files") {

    def bucketId = column[String]("bucket_id")
    def relativePath = column[String]("relative_path")
    def parentId = column[Option[String]]("parent_id")
    def contentType = column[Option[String]]("content_type")
    def contentMeta = column[Option[Array[Byte]]]("content_meta")
    def operationMeta = column[Option[Array[Byte]]]("operation")
    def resourceId = column[String]("resource_id")
    def size = column[Long]("size")
    def creationTime = column[Option[Long]]("creation_time")
    // we only store this to later check if the file has not been modified
    def lastModifiedTime = column[Option[Long]]("last_modified_time")
    def title = column[Option[String]]("title")
    def description = column[Option[String]]("description")

    def parentIdx = index("parent_id_idx", (bucketId, parentId))
    def bucketIdx = index("bucket_id_idx", bucketId)
    def hashIdx = index("hash_idx", resourceId)
    def pk = primaryKey("resources_pk", (bucketId, resourceId))

    def * = (bucketId, parentId, relativePath, resourceId, size, contentType, contentMeta, operationMeta, creationTime, lastModifiedTime, title, description) <>
      ((ResourceRow.apply _).tupled, ResourceRow.unapply)
  }

  val innerTable = TableQuery[LocalFilesSchema]

  def createIfNotExists: DBIO[Unit] =
    innerTable.schema.createIfNotExists

  def queryByParentId(bucketId: String, parentId: String) =
    innerTable
      .filter(_.bucketId === bucketId)
      .filter(_.parentId === parentId)

  def queryByHash(bucketId: String, hash: String): Query[LocalFilesSchema, ResourceRow, Seq] =
    innerTable
      .filter(_.bucketId === bucketId)
      .filter(_.resourceId === hash)

  def queryByPath(bucketId: String, path: String) =
    innerTable
      .filter(_.bucketId === bucketId)
      .filter(_.relativePath === path)

  def insert(resource: ResourceInfo) =
    innerTable += ResourceRow.fromResource(resource)

  def update(row: ResourceRow) =
    queryByHash(row.bucketId, row.hash).update(row)

  def update(resource: ResourceInfo) =
    queryByHash(resource.bucketId, resource.hash).update(ResourceRow.fromResource(resource))

  def insertOrUpdate(resource: ResourceInfo) =
    // ! The insertOrUpdate operation does not work in combination with a byte array field and hsqldb
    innerTable.insertOrUpdate(ResourceRow.fromResource(resource))

  def allForBucket(bucketId: String) =
    innerTable.filter(_.bucketId === bucketId)
}

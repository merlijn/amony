package nl.amony.service.resources.local.db

import nl.amony.service.resources.api.{ResourceInfo, ResourceMeta, ResourceMetaMessage}
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile

case class LocalFileRow(
   bucketId: String,
   parentId: Option[String],
   relativePath: String,
   hash: String,
   size: Long,
   contentType: Option[String],
   contentMeta: Option[Array[Byte]],
   creationTime: Option[Long],
   lastModifiedTime: Option[Long]) {

  def toResource(tags: Seq[String]): ResourceInfo = {
    ResourceInfo(
      bucketId = bucketId,
      parentId = parentId,
      path = relativePath,
      hash = hash,
      size = size,
      contentType = contentType,
      contentMeta = LocalFileRow.decodeMeta(contentMeta),
      tags = tags,
      creationTime = creationTime,
      lastModifiedTime = lastModifiedTime
    )
  }
}

object LocalFileRow  {

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

  def fromResource(resource: ResourceInfo): LocalFileRow = LocalFileRow(
    bucketId = resource.bucketId,
    parentId = resource.parentId,
    relativePath = resource.path,
    hash = resource.hash,
    size = resource.size,
    contentType = resource.contentType,
    contentMeta = encodeMeta(resource.contentMeta),
    creationTime = resource.creationTime,
    lastModifiedTime = resource.lastModifiedTime
  )
}

class LocalFilesTable[P <: JdbcProfile](val dbConfig: DatabaseConfig[P]) {

  import dbConfig.profile.api._

  class LocalFilesSchema(ttag: slick.lifted.Tag) extends Table[LocalFileRow](ttag, "files") {

    def bucketId = column[String]("bucket_id")
    def relativePath = column[String]("relative_path")
    def parentId = column[Option[String]]("parent_id")
    def contentType = column[Option[String]]("content_type")
    def contentMeta = column[Option[Array[Byte]]]("content_meta")
    def resourceId = column[String]("resource_id")
    def size = column[Long]("size")
    def creationTime = column[Option[Long]]("creation_time")
    // we only store this to later check if the file has not been modified
    def lastModifiedTime = column[Option[Long]]("last_modified_time")

    def parentIdx = index("parent_id_idx", (bucketId, parentId))
    def bucketIdx = index("bucket_id_idx", bucketId)
    def hashIdx = index("hash_idx", resourceId)
    def pk = primaryKey("resources_pk", (bucketId, resourceId))

    def * = (bucketId, parentId, relativePath, resourceId, size, contentType, contentMeta, creationTime, lastModifiedTime) <> ((LocalFileRow.apply _).tupled, LocalFileRow.unapply)
  }

  val innerTable = TableQuery[LocalFilesSchema]

  def createIfNotExists: DBIO[Unit] =
    innerTable.schema.createIfNotExists

  def queryByParentId(bucketId: String, parentId: String) =
    innerTable
      .filter(_.bucketId === bucketId)
      .filter(_.parentId === parentId)

  def queryByHash(bucketId: String, hash: String): Query[LocalFilesSchema, LocalFileRow, Seq] =
    innerTable
      .filter(_.bucketId === bucketId)
      .filter(_.resourceId === hash)

  def queryByPath(bucketId: String, path: String) =
    innerTable
      .filter(_.bucketId === bucketId)
      .filter(_.relativePath === path)

  def insert(resource: ResourceInfo) =
    innerTable += LocalFileRow.fromResource(resource)

  def insertOrUpdate(resource: ResourceInfo) =
    // This does not work in combination with a byte array field and hsqldb
    innerTable.insertOrUpdate(LocalFileRow.fromResource(resource))

  def allForBucket(bucketId: String) =
    innerTable.filter(_.bucketId === bucketId)
}

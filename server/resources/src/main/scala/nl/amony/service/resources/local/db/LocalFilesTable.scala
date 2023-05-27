package nl.amony.service.resources.local.db

import nl.amony.service.resources.api.Resource
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile

case class LocalFileRow(
   bucketId: String,
   parentId: Option[String],
   relativePath: String,
   hash: String,
   size: Long,
   contentType: Option[String],
   creationTime: Option[Long],
   lastModifiedTime: Option[Long]) {

  def toResource(tags: Seq[String]): Resource = {
    Resource(
      bucketId = bucketId,
      path = relativePath,
      hash = hash,
      size = size,
      contentType = contentType,
      tags = tags,
      creationTime = creationTime,
      lastModifiedTime = lastModifiedTime
    )
  }
}

object LocalFileRow {
  def fromResource(resource: Resource): LocalFileRow = LocalFileRow(
    bucketId = resource.bucketId,
    parentId = resource.parentId,
    relativePath = resource.path,
    hash = resource.hash,
    size = resource.size,
    contentType = resource.contentType,
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
    def hash = column[String]("hash")
    def size = column[Long]("size")
    def creationTime = column[Option[Long]]("creation_time")
    // we only store this to later check if the file has not been modified
    def lastModifiedTime = column[Option[Long]]("last_modified_time")

    def bucketIdx = index("bucket_id_idx", bucketId)
    def hashIdx = index("hash_idx", hash)
    def pk = primaryKey("resources_pk", (bucketId, relativePath))

    def * = (bucketId, parentId, relativePath, hash, size, contentType, creationTime, lastModifiedTime) <> ((LocalFileRow.apply _).tupled, LocalFileRow.unapply)
  }

  private val innerTable = TableQuery[LocalFilesSchema]

  def createIfNotExists: DBIO[Unit] =
    innerTable.schema.createIfNotExists

  def queryByHash(bucketId: String, hash: String): Query[LocalFilesSchema, LocalFileRow, Seq] =
    innerTable
      .filter(_.bucketId === bucketId)
      .filter(_.hash === hash)

  def queryByPath(bucketId: String, path: String) =
    innerTable
      .filter(_.bucketId === bucketId)
      .filter(_.relativePath === path)

  def insertOrUpdate(resource: Resource) =
    innerTable.insertOrUpdate(LocalFileRow.fromResource(resource))

  def allForBucket(bucketId: String) =
    innerTable.filter(_.bucketId === bucketId)
}

package nl.amony.service.resources.local.db

import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile

case class ResourceTagRow(bucketId: String, resourceId: String, tag: String)

class ResourceTagsTable[P <: JdbcProfile](val dbConfig: DatabaseConfig[P]) {

  import dbConfig.profile.api._

  class ResourceTags(ttag: Tag) extends Table[ResourceTagRow](ttag, "resource_tags") {

    def bucketId: Rep[String] = column[String]("bucket_id")
    def resourceId = column[String]("resource_id")
    def tag = column[String]("tag")
    def resourceId_idx = index("resource_idx", (bucketId, resourceId))
    def pk = primaryKey("resource_tags_pk", (bucketId, resourceId, tag))

    def * = (bucketId, resourceId, tag) <> ((ResourceTagRow.apply _).tupled, ResourceTagRow.unapply)
  }

  val innerTable = TableQuery[ResourceTags]

  def createIfNotExists: DBIO[Unit] = innerTable.schema.createIfNotExists

  def queryById(bucketId: String, resourceId: String) =
    innerTable.filter(r => r.bucketId === bucketId && r.resourceId === resourceId)

  def getTags(bucketId: String, resourceId: String) =
    queryById(bucketId, resourceId).map(_.tag)
    
  def removeTags(bucketId: String, resourceId: String, tags: Seq[String]) =
    queryById(bucketId, resourceId).filter(_.tag.inSet(tags)).delete

  def insert(bucketId: String, resourceId: String, tag: String): DBIO[Int] =
    innerTable += ResourceTagRow(bucketId, resourceId, tag)

  def insert(bucketId: String, resourceId: String, tags: Seq[String]): DBIO[Option[Int]] = {
    if (tags.isEmpty)
      DBIO.successful(None)
    else
      innerTable ++= tags.map(ResourceTagRow(bucketId, resourceId, _))
  }

  def delete(bucketId: String, resourceId: String): DBIO[Int] =
    queryById(bucketId, resourceId).delete
}

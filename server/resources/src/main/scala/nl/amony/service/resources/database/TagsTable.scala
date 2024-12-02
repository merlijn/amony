package nl.amony.service.resources.database

import scribe.Logging
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile

import scala.concurrent.ExecutionContext

case class ResourceTagRow(bucketId: String, resourceId: String, tag: String)

class TagsTable[P <: JdbcProfile](val dbConfig: DatabaseConfig[P]) extends Logging {

  import dbConfig.profile.api.*

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
    
  def removeTags(bucketId: String, resourceId: String, tags: Set[String]) =
    queryById(bucketId, resourceId).filter(_.tag.inSet(tags)).delete

  def insert(bucketId: String, resourceId: String, tags: Set[String])(using ec: ExecutionContext): DBIO[Int] =
    if (tags.isEmpty)
      DBIO.successful(0)
    else
      (innerTable ++= tags.map(ResourceTagRow(bucketId, resourceId, _))).map(_.getOrElse(0))

  def insertOrUpdate(bucketId: String, resourceId: String, tags: Set[String])(using ec: ExecutionContext) =
    for {
      currentTags <- queryById(bucketId, resourceId).map(_.tag).result.map(_.toSet)
      removedTags <- removeTags(bucketId, resourceId, currentTags -- tags)
      addedTags   <- insert(bucketId, resourceId, tags -- currentTags)
    } yield addedTags + removedTags

  def delete(bucketId: String, resourceId: String): DBIO[Int] =
    queryById(bucketId, resourceId).delete
}

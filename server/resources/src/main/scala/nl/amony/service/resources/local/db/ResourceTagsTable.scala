package nl.amony.service.resources.local.db

import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile

class ResourceTagsTable[P <: JdbcProfile](val dbConfig: DatabaseConfig[P]) {

  import dbConfig.profile.api._

  private class ResourceTags(ttag: Tag) extends Table[(String, String)](ttag, "resource_tags") {

    def resourceId = column[String]("resource_id")
    def tag = column[String]("tag")
    def resourceId_idx = index("resource_id_idx", resourceId)
    def pk = primaryKey("resource_tags_pk", (resourceId, tag))

    def * = (resourceId, tag)
  }

  private val innerTable = TableQuery[ResourceTags]

  def createIfNotExists: DBIO[Unit] = innerTable.schema.createIfNotExists

  def getTags(hash: String) =
    innerTable.filter(_.resourceId === hash).map(_.tag)

  def insert(resourceId: String, tag: String): DBIO[Int] = innerTable += (resourceId, tag)

  def insert(resourceId: String, tags: Seq[String]): DBIO[Option[Int]] = {
    if (tags.isEmpty)
      DBIO.successful(None)
    else
      innerTable ++= tags.map((resourceId, _))
  }
}

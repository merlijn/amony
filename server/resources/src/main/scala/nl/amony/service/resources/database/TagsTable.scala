package nl.amony.service.resources.database

import scribe.Logging
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile

import scala.concurrent.ExecutionContext

case class TagRow(
  id: Option[Int],
  label: String
)

class TagsTable[P <: JdbcProfile](val dbConfig: DatabaseConfig[P]) extends Logging {

  import dbConfig.profile.api.*

  class TagsSchema(ttag: slick.lifted.Tag) extends Table[TagRow](ttag, "tags") {
    def id         = column[Int]("id", O.PrimaryKey, O.AutoInc)
    def label      = column[String]("label", O.Unique)
    def labelIndex = index("tags_label_idx", label, unique = true)
    
    def * = (id.?, label) <> ((TagRow.apply _).tupled, TagRow.unapply)
  }

  val table = TableQuery[TagsSchema]
  
  def upsert(tagRow: TagRow) = 
    table.insertOrUpdate(tagRow)
    
  def getTag(id: Int) = {
    table.filter(_.id === id).result.headOption
  }

  def upsertMissingLabels(labels: Set[String])(using ec: ExecutionContext) = {
    for {
      existingLabels <- table.filter(_.label.inSet(labels)).result
      missing = labels -- existingLabels.map(_.label)
      added  <- addLabels(missing)
    } yield existingLabels ++ added
  }
  
  private def addLabels(labels: Set[String])(using ec: ExecutionContext) = 
    if (labels.isEmpty) 
      DBIO.successful(Seq.empty[TagRow]) 
    else
      (table ++= labels.map(TagRow(None, _))).flatMap(_ => table.filter(_.label.inSet(labels)).result)

  def getTagsByIds(ids: Seq[Int]) = {
    table.filter(_.id.inSet(ids)).result
  }

  def getTagsByLabels(labels: Seq[String]) =
    table.filter(_.label.inSet(labels)).result

  def renameTag(id: Int, newLabel: String) = {
    table.filter(_.id === id).map(_.label).update(newLabel)
  }
  
  def getAllLabels() = {
    table.map(_.label).result
  }
}
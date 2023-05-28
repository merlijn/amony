package nl.amony.service.resources.local.db

import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile

class OperationsTable[P <: JdbcProfile](val dbConfig: DatabaseConfig[P]) {

  import dbConfig.profile.api._

  case class OperationRow(directoryId: String, inputId: String, operationData: Array[Byte], operationId: String, outputId: String)

  private class Operations(ttag: Tag) extends Table[OperationRow](ttag, "operations") {

    def bucketId = column[String]("bucket_id")
    def inputId: Rep[String] = column[String]("input_id")
    def operationData = column[Array[Byte]]("operation_data")
    def operationId: Rep[String] = column[String]("operation_id")
    def outputId = column[String]("output_id")

    def pk = primaryKey("operations_pk", (bucketId, inputId, operationId, outputId))
    def * = (bucketId, inputId, operationData, operationId, outputId) <> ((OperationRow.apply _).tupled, OperationRow.unapply)
  }

  private val innerTable = TableQuery[Operations]

  def createIfNotExists: DBIO[Unit] =
    innerTable.schema.createIfNotExists

  def insert(operationRow: OperationRow): DBIO[Int] =
    innerTable += operationRow
}

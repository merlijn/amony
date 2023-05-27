package nl.amony.service.resources.local.db

import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile

class OperationsTable[P <: JdbcProfile](val dbConfig: DatabaseConfig[P]) {

  import dbConfig.profile.api._

  case class OperationRow(directoryId: String, operation: Array[Byte], operationId: String, output: String)

  private class Operations(ttag: Tag) extends Table[OperationRow](ttag, "operations") {

    def directoryId = column[String]("input")

    def operation = column[Array[Byte]]("operation")

    def operationId: Rep[String] = column[String]("operation_id")

    def output = column[String]("output")

    def pk = primaryKey("operations_pk", (directoryId, operation))

    def * = (directoryId, operation, operationId, output) <> ((OperationRow.apply _).tupled, OperationRow.unapply)
  }
}

package nl.amony.service.resources.database

import cats.effect.IO
import com.dimafeng.testcontainers.GenericContainer
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import org.scalatest.wordspec.AnyWordSpecLike
import org.testcontainers.containers.wait.strategy.Wait
import cats.effect.unsafe.implicits.global
import scribe.Logging

class SkunkDatabaseSpec extends AnyWordSpecLike with TestContainerForAll with Logging {

  override val containerDef: GenericContainer.Def[GenericContainer] =
    GenericContainer.Def(
      "postgres:17.2",
      exposedPorts = Seq(5432),
      waitStrategy = Wait.forLogMessage(".*database system is ready to accept connections.*", 2),
      env = Map(
        "POSTGRES_USER" -> "test",
        "PGUSER" -> "test",
        "POSTGRES_PASSWORD" -> "test",
        "POSTGRES_DB" -> "test"
      )
    )

  val row =
    SkunkResourceRow(
      bucket_id = "bucket",
      resource_id = "resource",
      user_id = "user",
      relative_path = "path",
      hash = "hash",
      size = 0,
      content_type = None,
      content_meta_tool_name = None,
      content_meta_tool_data = None,
      creation_time = None,
      last_modified_time = None,
      title = None,
      description = None
    )

  "The Database" should {
    "start up" in {
      withContainers { container =>

        logger.info(s"Container IP: ${container.containerIpAddress}")
        logger.info(s"Container Port: ${container.mappedPort(5432)}")

        val dbConfig = SkunkDatabaseConfig(
          host = container.containerIpAddress,
          port = container.mappedPort(5432),
          database = "test",
          user = "test",
          password = Some("test")
        )

        SkunkDatabase.make(dbConfig).use(db =>
          logger.info(s"--- executing queries")
          for {
            _           <- db.insertRow(row)
            returnedRow <- db.getRowById(row.bucket_id, row.resource_id)
          } yield logger.info(s"returned row: $returnedRow")
        ).unsafeRunSync()
      }
    }
  }
}

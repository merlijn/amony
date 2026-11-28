package nl.amony.app

import java.nio.file.{Files, Paths}

import com.dimafeng.testcontainers.GenericContainer
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.testcontainers.containers.wait.strategy.Wait

import nl.amony.platform.database.DatabaseConfig

class ExportDatabaseSchemaSpec extends AnyWordSpecLike with TestContainerForAll with Matchers {

  override val containerDef: GenericContainer.Def[GenericContainer] =
    GenericContainer.Def(
      "postgres:17.2",
      exposedPorts = Seq(5432),
      waitStrategy = Wait.forLogMessage(".*database system is ready to accept connections.*", 2),
      env          = Map(
        "POSTGRES_USER"     -> "test",
        "PGUSER"            -> "test",
        "POSTGRES_PASSWORD" -> "test",
        "POSTGRES_DB"       -> "test"
      )
    )

  "ExportDatabaseSchema" when {

    "dumping the schema" should {

      "export DDL schema after migrations" in withContainers { container =>
        val outputFile = ExportDatabaseSchema.dumpSchema(
          DatabaseConfig(container.containerIpAddress, container.mappedPort(5432), "test", "test", 3, "test"),
          container.containerId,
          Paths.get("target", "test-schema.sql")
        )

        Files.readString(outputFile) should include("CREATE TABLE")
      }
    }
  }
}

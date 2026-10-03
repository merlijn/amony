package nl.amony

import java.nio.file.{Files, Path, Paths}
import scala.sys.process.*

import cats.effect.unsafe.implicits.global
import com.dimafeng.testcontainers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import scribe.Logging

/**
 * Dumps the PostgreSQL schema that results from applying every Liquibase migration.
 *
 * This lives in the test sources (and is exposed through `sbt exportDatabaseSchema`) because it
 * starts a throwaway PostgreSQL Testcontainer, which is a test-scoped dependency. It backs both
 * [[nl.amony.modules.resources.dal.ResourceDatabaseSpec]] and the `exportDatabaseSchema` task.
 */
object ExportDatabaseSchema extends Logging:

  val defaultOutput: Path = Paths.get("target", "amony-schema.sql")

  private val PostgresImage = "postgres:17.2"

  /** Starts a throwaway PostgreSQL container, migrates it, and writes the schema dump to `output`. */
  def dumpSchema(output: Path = defaultOutput): Path =
    val container = newContainer()
    container.start()
    try
      val database = DatabaseConfig(
        host     = container.containerIpAddress,
        port     = container.mappedPort(5432),
        database = "test",
        username = "test",
        password = "test",
        poolSize = 3
      )
      dumpSchema(database, container.containerId, output)
    finally container.stop()

  /** Migrates `database` and dumps its schema with `pg_dump` running inside `containerId`. */
  def dumpSchema(database: DatabaseConfig, containerId: String, output: Path): Path =
    App.runDatabaseMigrations(database).unsafeRunSync()

    val rawOutput = Files.createTempFile("amony-schema-raw", ".sql")
    try
      val exitCode = Seq(
        "docker",
        "exec",
        "-i",
        containerId,
        "pg_dump",
        "-U",
        database.username,
        "-d",
        database.database,
        "--schema-only",
        "--no-owner",
        "--no-acl",
        "--exclude-table=databasechangelog",
        "--exclude-table=databasechangeloglock"
      ).#>(rawOutput.toFile).!

      require(exitCode == 0, s"pg_dump exited with code $exitCode")

      writeNormalized(Files.readString(rawOutput), output)
      logger.info(s"Database schema exported to: ${output.toAbsolutePath}")
      output
    finally Files.deleteIfExists(rawOutput)

  private def newContainer(): GenericContainer =
    GenericContainer(
      PostgresImage,
      exposedPorts = Seq(5432),
      waitStrategy = Wait.forLogMessage(".*database system is ready to accept connections.*", 2),
      env          = Map(
        "POSTGRES_USER"     -> "test",
        "PGUSER"            -> "test",
        "POSTGRES_PASSWORD" -> "test",
        "POSTGRES_DB"       -> "test"
      )
    )

  /** Drops pg_dump noise (`SET`, `SELECT pg_catalog...`, comments) and collapses blank lines. */
  private def writeNormalized(rawContent: String, output: Path): Unit =
    val normalized = rawContent.linesIterator
      .filterNot(line =>
        line.trim.startsWith("SET ") ||
          line.trim.startsWith("SELECT pg_catalog.set_config") ||
          line.trim.startsWith("--")
      )
      .foldLeft(List.empty[String]) { (acc, line) =>
        if line.trim.isEmpty then
          acc.lastOption match
            case Some(last) if last.trim.nonEmpty => acc :+ ""
            case _                                => acc
        else acc :+ line
      }
      .mkString("\n")
      .trim

    val absolute = output.toAbsolutePath
    if absolute.getParent != null then Files.createDirectories(absolute.getParent)
    Files.writeString(absolute, normalized)

  def main(args: Array[String]): Unit =
    val output = args.headOption.map(Paths.get(_)).getOrElse(defaultOutput)
    dumpSchema(output)

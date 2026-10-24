package nl.amony.platform.database

import scala.util.Using

import cats.effect.{IO, Resource}
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor
import liquibase.{Contexts, Liquibase}
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.trace.TracerProvider
import scribe.Logging
import skunk.Session

object Database extends Logging:

  /**
   * Liquibase identifies applied change sets by this classpath path, so it must keep matching the historical
   * `db/00-changelog.yaml` location even though the file now lives in this module.
   */
  val Changelog: String = "db/00-changelog.yaml"

  def runMigrations(config: DatabaseConfig, contexts: Contexts = new Contexts()): IO[Unit] =
    config.getJdbcConnection.flatMap: connection =>
      IO.fromTry(Using(connection) { conn =>
        logger.info("Running database migrations...")
        val liquibaseDatabase = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(conn))
        new Liquibase(Changelog, new ClassLoaderResourceAccessor(), liquibaseDatabase).update(contexts)
      })

  def makePool(
    config: DatabaseConfig,
    contexts: Contexts = new Contexts()
  )(using TracerProvider[IO], MeterProvider[IO]): Resource[IO, Resource[IO, Session[IO]]] =
    for
      pool <- Session.Builder[IO]
                .withHost(config.host)
                .withPort(config.port)
                .withUserAndPassword(config.username, config.password)
                .withDatabase(config.database)
                .pooled(config.poolSize)
      _    <- Resource.eval(runMigrations(config, contexts))
    yield pool

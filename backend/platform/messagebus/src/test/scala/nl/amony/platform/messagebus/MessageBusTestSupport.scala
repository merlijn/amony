package nl.amony.platform.messagebus

import cats.effect.{IO, Resource}
import com.dimafeng.testcontainers.GenericContainer
import liquibase.Contexts
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.trace.TracerProvider
import skunk.Session

import nl.amony.platform.database.{Database, DatabaseConfig}

private[messagebus] object MessageBusTestSupport:

  def makeDatabasePool(container: GenericContainer)(
    using TracerProvider[IO],
    MeterProvider[IO]
  ): Resource[IO, Resource[IO, Session[IO]]] =
    Database.makePool(
      DatabaseConfig(
        host     = container.containerIpAddress,
        port     = container.mappedPort(5432),
        database = "test",
        username = "test",
        password = "test",
        poolSize = 4
      ),
      new Contexts("message-log")
    )

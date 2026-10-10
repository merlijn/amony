package nl.amony.lib.messagebus

import java.util.UUID
import scala.concurrent.duration.*

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.dimafeng.testcontainers.GenericContainer
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import io.circe.Json
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.testcontainers.containers.wait.strategy.Wait
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import skunk.codec.all.*
import skunk.implicits.*
import skunk.{Session, *}

import nl.amony.DatabaseConfig

class OutboxSpec extends AnyWordSpecLike with TestContainerForAll with Matchers:

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

  given meter: Meter[IO]   = Meter.noop[IO]
  given tracer: Tracer[IO] = Tracer.noop[IO]

  override def afterContainersStart(container: GenericContainer): Unit =
    applyMigration(configForContainer(container), "db/10-event-outbox.sql").unsafeRunSync()

  private def configForContainer(container: GenericContainer): DatabaseConfig =
    DatabaseConfig(
      host     = container.containerIpAddress,
      port     = container.mappedPort(5432),
      database = "test",
      username = "test",
      password = "test",
      poolSize = 4
    )

  private def makePool(config: DatabaseConfig): Resource[IO, Resource[IO, Session[IO]]] =
    Session.Builder[IO]
      .withHost(config.host)
      .withPort(config.port)
      .withUserAndPassword(config.username, config.password)
      .withDatabase(config.database)
      .pooled(config.poolSize)

  private def withOutbox[A](container: GenericContainer)(f: (Resource[IO, Session[IO]], EventOutbox) => IO[A]): A =
    makePool(configForContainer(container)).use(pool => f(pool, EventOutbox(pool))).unsafeRunSync()

  private def newTopic: String = s"test-${UUID.randomUUID()}"

  private def payload(n: Int): Json = Json.obj("n" -> Json.fromInt(n))

  private def publish(pool: Resource[IO, Session[IO]], outbox: EventOutbox, topic: String, n: Int): IO[Unit] =
    pool.use(session => outbox.enqueue(session, topic, payload(n)))

  private def countRows(pool: Resource[IO, Session[IO]], topic: String): IO[Long] =
    pool.use(session => session.prepare(sql"select count(*) from event_outbox where topic = $varchar".query(int8)).flatMap(_.unique(topic)))

  private def applyMigration(config: DatabaseConfig, resource: String): IO[Unit] =
    IO.blocking {
      val stream     = Option(getClass.getClassLoader.getResourceAsStream(resource)).getOrElse(sys.error(s"Missing resource: $resource"))
      val text       = scala.io.Source.fromInputStream(stream, "UTF-8").mkString
      val statements = text.linesIterator.filterNot(_.trim.startsWith("--")).mkString("\n").split(";").map(_.trim).filter(_.nonEmpty)
      stream.close()
      statements.toList
    }.flatMap { statements =>
      config.getJdbcConnection.flatMap { connection =>
        IO.blocking {
          val statement = connection.createStatement()
          try statements.foreach(statement.execute)
          finally
            statement.close()
            connection.close()
        }
      }
    }

  "EventOutbox" should {

    "claim pending events one at a time in insertion order" in withContainers { container =>
      withOutbox(container) { (pool, outbox) =>
        val topic = newTopic
        for
          _ <- (1 to 3).toList.traverse_(n => publish(pool, outbox, topic, n))
          a <- outbox.claimOldest(topic)
          b <- outbox.claimOldest(topic)
          c <- outbox.claimOldest(topic)
          d <- outbox.claimOldest(topic)
        yield
          a.map(_.payload) shouldBe Some(payload(1))
          b.map(_.payload) shouldBe Some(payload(2))
          c.map(_.payload) shouldBe Some(payload(3))
          a.map(_.attempts) shouldBe Some(1)
          d shouldBe None
      }
    }

    "not claim events that are already processed or failed" in withContainers { container =>
      withOutbox(container) { (pool, outbox) =>
        val topic = newTopic
        for
          _      <- publish(pool, outbox, topic, 1)
          _      <- publish(pool, outbox, topic, 2)
          first  <- outbox.claimOldest(topic)
          _      <- outbox.markProcessed(first.get.id)
          second <- outbox.claimOldest(topic)
          _      <- outbox.markFailed(second.get.id, "boom")
          again  <- outbox.claimOldest(topic)
        yield
          first.map(_.payload) shouldBe Some(payload(1))
          second.map(_.payload) shouldBe Some(payload(2))
          again shouldBe None
      }
    }

    "recover events left claimed by a stopped consumer" in withContainers { container =>
      withOutbox(container) { (pool, outbox) =>
        val topic = newTopic
        for
          _      <- publish(pool, outbox, topic, 1)
          first  <- outbox.claimOldest(topic)
          held   <- outbox.claimOldest(topic)
          _      <- outbox.resetClaimed(topic)
          second <- outbox.claimOldest(topic)
        yield
          first.map(_.attempts) shouldBe Some(1)
          held shouldBe None
          second.map(_.attempts) shouldBe Some(2)
      }
    }

    "return a failing event to pending with its error recorded" in withContainers { container =>
      withOutbox(container) { (pool, outbox) =>
        val topic = newTopic
        for
          _       <- publish(pool, outbox, topic, 1)
          first   <- outbox.claimOldest(topic)
          _       <- outbox.releaseForRetry(first.get.id, "boom")
          retried <- outbox.claimOldest(topic)
        yield
          retried.map(_.status) shouldBe Some(OutboxStatus.Claimed)
          retried.map(_.attempts) shouldBe Some(2)
          retried.map(_.lastError) shouldBe Some(Some("boom"))
      }
    }

    "let competing consumers claim different events" in withContainers { container =>
      withOutbox(container) { (pool, outbox) =>
        val topic = newTopic
        for
          _      <- (1 to 2).toList.traverse_(n => publish(pool, outbox, topic, n))
          claims <- (outbox.claimOldest(topic), outbox.claimOldest(topic)).parTupled
          (a, b)  = claims
        yield
          a.map(_.id) should not be b.map(_.id)
          List(a, b).flatten.map(_.status) shouldBe List(OutboxStatus.Claimed, OutboxStatus.Claimed)
      }
    }

    "purge processed and failed events past retention without touching pending ones" in withContainers { container =>
      withOutbox(container) { (pool, outbox) =>
        val topic = newTopic
        for
          _      <- (1 to 3).toList.traverse_(n => publish(pool, outbox, topic, n))
          first  <- outbox.claimOldest(topic)
          second <- outbox.claimOldest(topic)
          _      <- outbox.markProcessed(first.get.id)
          _      <- outbox.markFailed(second.get.id, "boom")
          _      <- IO.sleep(50.millis)
          _      <- outbox.purge(java.time.Duration.ZERO)
          left   <- countRows(pool, topic)
        yield left shouldBe 1
      }
    }
  }

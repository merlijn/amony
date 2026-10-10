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

  private val longTtl = java.time.Duration.ofMinutes(5)
  private val noTtl   = java.time.Duration.ZERO

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

    "claim pending events in batches, oldest first" in withContainers { container =>
      withOutbox(container) { (pool, outbox) =>
        val topic = newTopic
        for
          _  <- (1 to 5).toList.traverse_(n => publish(pool, outbox, topic, n))
          b1 <- outbox.claim(topic, longTtl, 2)
          b2 <- outbox.claim(topic, longTtl, 2)
          b3 <- outbox.claim(topic, longTtl, 2)
          b4 <- outbox.claim(topic, longTtl, 2)
        yield
          b1.map(_.payload).toSet shouldBe Set(payload(1), payload(2))
          b1.map(_.status) shouldBe List(OutboxStatus.Claimed, OutboxStatus.Claimed)
          b1.map(_.attempts) shouldBe List(1, 1)
          b2.map(_.payload).toSet shouldBe Set(payload(3), payload(4))
          b3.map(_.payload) shouldBe List(payload(5))
          b4 shouldBe Nil
      }
    }

    "not claim events that are already processed or failed" in withContainers { container =>
      withOutbox(container) { (pool, outbox) =>
        val topic = newTopic
        for
          _     <- publish(pool, outbox, topic, 1)
          _     <- publish(pool, outbox, topic, 2)
          rows  <- outbox.claim(topic, longTtl, 10)
          _     <- outbox.markProcessed(rows.head.id)
          _     <- outbox.markFailed(rows(1).id, "boom")
          again <- outbox.claim(topic, longTtl, 10)
        yield
          rows.map(_.id).distinct.size shouldBe 2
          again shouldBe Nil
      }
    }

    "reclaim an event abandoned by a crashed consumer once its claim TTL has elapsed" in withContainers { container =>
      withOutbox(container) { (pool, outbox) =>
        val topic = newTopic
        for
          _      <- publish(pool, outbox, topic, 1)
          first  <- outbox.claim(topic, longTtl, 10)
          held   <- outbox.claim(topic, longTtl, 10)
          _      <- IO.sleep(50.millis)
          second <- outbox.claim(topic, noTtl, 10)
        yield
          first.map(_.attempts) shouldBe List(1)
          held shouldBe Nil
          second.map(_.attempts) shouldBe List(2)
      }
    }

    "return a failing event to pending with its error recorded" in withContainers { container =>
      withOutbox(container) { (pool, outbox) =>
        val topic = newTopic
        for
          _       <- publish(pool, outbox, topic, 1)
          rows    <- outbox.claim(topic, longTtl, 10)
          _       <- outbox.releaseForRetry(rows.head.id, "boom")
          retried <- outbox.claim(topic, longTtl, 10)
        yield
          retried.map(_.status) shouldBe List(OutboxStatus.Claimed)
          retried.map(_.attempts) shouldBe List(2)
          retried.map(_.lastError) shouldBe List(Some("boom"))
      }
    }

    "let competing consumers claim disjoint batches without blocking" in withContainers { container =>
      withOutbox(container) { (pool, outbox) =>
        val topic = newTopic
        for
          _      <- (1 to 10).toList.traverse_(n => publish(pool, outbox, topic, n))
          claims <- (outbox.claim(topic, longTtl, 10), outbox.claim(topic, longTtl, 10)).parTupled
          (a, b)  = claims
        yield
          (a.map(_.id) ++ b.map(_.id)).distinct.size shouldBe 10
          a.map(_.status).distinct shouldBe List(OutboxStatus.Claimed)
      }
    }

    "purge processed and failed events past retention without touching pending ones" in withContainers { container =>
      withOutbox(container) { (pool, outbox) =>
        val topic = newTopic
        for
          _    <- (1 to 3).toList.traverse_(n => publish(pool, outbox, topic, n))
          rows <- outbox.claim(topic, longTtl, 2)
          _    <- outbox.markProcessed(rows.head.id)
          _    <- outbox.markFailed(rows(1).id, "boom")
          _    <- IO.sleep(50.millis)
          _    <- outbox.purge(noTtl)
          left <- countRows(pool, topic)
        yield left shouldBe 1
      }
    }
  }

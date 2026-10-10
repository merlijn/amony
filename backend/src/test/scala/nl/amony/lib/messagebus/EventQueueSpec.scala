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

import nl.amony.{App, DatabaseConfig}

class EventQueueSpec extends AnyWordSpecLike with TestContainerForAll with Matchers:

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

  private def configForContainer(container: GenericContainer): DatabaseConfig =
    DatabaseConfig(
      host     = container.containerIpAddress,
      port     = container.mappedPort(5432),
      database = "test",
      username = "test",
      password = "test",
      poolSize = 4
    )

  private def withQueue[A](container: GenericContainer)(f: (Resource[IO, Session[IO]], EventQueue) => IO[A]): A =
    App.makeDatabasePool(configForContainer(container)).use(pool => f(pool, EventQueue(pool))).unsafeRunSync()

  private def newTopic: String = s"test-${UUID.randomUUID()}"

  private def payload(n: Int): Json = Json.obj("n" -> Json.fromInt(n))

  private def publish(pool: Resource[IO, Session[IO]], queue: EventQueue, topic: String, n: Int): IO[Unit] =
    pool.use(session => queue.enqueue(session, topic, payload(n)))

  private def countRows(pool: Resource[IO, Session[IO]], topic: String): IO[Long] =
    pool.use(session => session.prepare(sql"select count(*) from event_queue where topic = $varchar".query(int8)).flatMap(_.unique(topic)))

  "EventQueue" should {

    "claim pending events one at a time in insertion order" in withContainers { container =>
      withQueue(container) { (pool, queue) =>
        val topic = newTopic
        for
          _ <- (1 to 3).toList.traverse_(n => publish(pool, queue, topic, n))
          a <- queue.claimOldest(topic)
          b <- queue.claimOldest(topic)
          c <- queue.claimOldest(topic)
          d <- queue.claimOldest(topic)
        yield
          a.map(_.payload) shouldBe Some(payload(1))
          b.map(_.payload) shouldBe Some(payload(2))
          c.map(_.payload) shouldBe Some(payload(3))
          a.map(_.attempts) shouldBe Some(1)
          d shouldBe None
      }
    }

    "not claim events that are already processed or failed" in withContainers { container =>
      withQueue(container) { (pool, queue) =>
        val topic = newTopic
        for
          _      <- publish(pool, queue, topic, 1)
          _      <- publish(pool, queue, topic, 2)
          first  <- queue.claimOldest(topic)
          _      <- queue.markProcessed(first.get.id)
          second <- queue.claimOldest(topic)
          _      <- queue.markFailed(second.get.id, "boom")
          again  <- queue.claimOldest(topic)
        yield
          first.map(_.payload) shouldBe Some(payload(1))
          second.map(_.payload) shouldBe Some(payload(2))
          again shouldBe None
      }
    }

    "recover events left claimed by a stopped consumer" in withContainers { container =>
      withQueue(container) { (pool, queue) =>
        val topic = newTopic
        for
          _      <- publish(pool, queue, topic, 1)
          first  <- queue.claimOldest(topic)
          held   <- queue.claimOldest(topic)
          _      <- queue.resetClaimed(topic)
          second <- queue.claimOldest(topic)
        yield
          first.map(_.attempts) shouldBe Some(1)
          held shouldBe None
          second.map(_.attempts) shouldBe Some(2)
      }
    }

    "return a failing event to pending with its error recorded" in withContainers { container =>
      withQueue(container) { (pool, queue) =>
        val topic = newTopic
        for
          _       <- publish(pool, queue, topic, 1)
          first   <- queue.claimOldest(topic)
          _       <- queue.releaseForRetry(first.get.id, "boom")
          retried <- queue.claimOldest(topic)
        yield
          retried.map(_.status) shouldBe Some(EventQueueStatus.Claimed)
          retried.map(_.attempts) shouldBe Some(2)
          retried.map(_.lastError) shouldBe Some(Some("boom"))
      }
    }

    "let competing consumers claim different events" in withContainers { container =>
      withQueue(container) { (pool, queue) =>
        val topic = newTopic
        for
          _      <- (1 to 2).toList.traverse_(n => publish(pool, queue, topic, n))
          claims <- (queue.claimOldest(topic), queue.claimOldest(topic)).parTupled
          (a, b)  = claims
        yield
          a.map(_.id) should not be b.map(_.id)
          List(a, b).flatten.map(_.status) shouldBe List(EventQueueStatus.Claimed, EventQueueStatus.Claimed)
      }
    }

    "purge processed and failed events past retention without touching pending ones" in withContainers { container =>
      withQueue(container) { (pool, queue) =>
        val topic = newTopic
        for
          _      <- (1 to 3).toList.traverse_(n => publish(pool, queue, topic, n))
          first  <- queue.claimOldest(topic)
          second <- queue.claimOldest(topic)
          _      <- queue.markProcessed(first.get.id)
          _      <- queue.markFailed(second.get.id, "boom")
          _      <- IO.sleep(50.millis)
          _      <- queue.purge(java.time.Duration.ZERO)
          left   <- countRows(pool, topic)
        yield left shouldBe 1
      }
    }
  }

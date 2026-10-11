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

class MessageQueueSpec extends AnyWordSpecLike with TestContainerForAll with Matchers:

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

  private def withQueue[A](container: GenericContainer)(f: (Resource[IO, Session[IO]], MessageQueue) => IO[A]): A =
    App.makeDatabasePool(configForContainer(container)).use(pool => f(pool, MessageQueue(pool))).unsafeRunSync()

  private def newTopic: String = s"test-${UUID.randomUUID()}"

  private def payload(n: Int): Json = Json.obj("n" -> Json.fromInt(n))

  private def nOf(json: Json): Int = json.hcursor.get[Int]("n").getOrElse(throw new RuntimeException(s"missing 'n' in $json"))

  private def publish(pool: Resource[IO, Session[IO]], queue: MessageQueue, topic: String, n: Int): IO[Unit] =
    pool.use(session => queue.enqueue(session, topic, payload(n)))

  private def countRows(pool: Resource[IO, Session[IO]], topic: String): IO[Long] =
    pool.use(session => session.prepare(sql"select count(*) from message_queue where topic = $varchar".query(int8)).flatMap(_.unique(topic)))

  private def consumerState(pool: Resource[IO, Session[IO]], topic: String, consumerId: String): IO[(Int, Option[String])] =
    pool.use(
      _.prepare(sql"select attempts, last_error from message_consumer where topic = $varchar and consumer_id = $varchar".query(int4 *: text.opt))
        .flatMap(_.unique((topic, consumerId)))
    )

  private def readAll(queue: MessageQueue, topic: String, consumerId: String): IO[List[Int]] =
    queue.nextUnprocessed(topic, consumerId).flatMap {
      case None      => IO.pure(Nil)
      case Some(row) => queue.commitOffset(topic, consumerId, row.id).as(nOf(row.payload)).flatMap(n => readAll(queue, topic, consumerId).map(n :: _))
    }

  "MessageQueue" should {

    "read messages in insertion order for a consumer" in withContainers { container =>
      withQueue(container) { (pool, queue) =>
        val topic = newTopic
        for
          _  <- queue.ensureConsumer(topic, "consumer", 0)
          _  <- (1 to 3).toList.traverse_(n => publish(pool, queue, topic, n))
          xs <- readAll(queue, topic, "consumer")
        yield xs shouldBe List(1, 2, 3)
      }
    }

    "start a new consumer at the latest message" in withContainers { container =>
      withQueue(container) { (pool, queue) =>
        val topic = newTopic
        for
          _    <- (1 to 2).toList.traverse_(n => publish(pool, queue, topic, n))
          max  <- queue.latestId(topic)
          _    <- queue.ensureConsumer(topic, "consumer", max)
          none <- queue.nextUnprocessed(topic, "consumer")
          _    <- publish(pool, queue, topic, 3)
          next <- queue.nextUnprocessed(topic, "consumer")
        yield
          none shouldBe None
          next.map(row => nOf(row.payload)) shouldBe Some(3)
      }
    }

    "record a failure without advancing the offset" in withContainers { container =>
      withQueue(container) { (pool, queue) =>
        val topic = newTopic
        for
          _     <- queue.ensureConsumer(topic, "consumer", 0)
          _     <- publish(pool, queue, topic, 1)
          first <- queue.nextUnprocessed(topic, "consumer")
          _     <- queue.recordFailure(topic, "consumer", "boom")
          again <- queue.nextUnprocessed(topic, "consumer")
          state <- consumerState(pool, topic, "consumer")
        yield
          again.map(_.id) shouldBe first.map(_.id)
          state shouldBe (1, Some("boom"))
      }
    }

    "let independent consumers each read the whole log" in withContainers { container =>
      withQueue(container) { (pool, queue) =>
        val topic = newTopic
        for
          _ <- queue.ensureConsumer(topic, "a", 0)
          _ <- queue.ensureConsumer(topic, "b", 0)
          _ <- (1 to 3).toList.traverse_(n => publish(pool, queue, topic, n))
          a <- readAll(queue, topic, "a")
          b <- readAll(queue, topic, "b")
        yield
          a shouldBe List(1, 2, 3)
          b shouldBe List(1, 2, 3)
      }
    }

    "purge messages every consumer has processed" in withContainers { container =>
      withQueue(container) { (pool, queue) =>
        val topic = newTopic
        for
          _    <- queue.ensureConsumer(topic, "consumer", 0)
          _    <- (1 to 2).toList.traverse_(n => publish(pool, queue, topic, n))
          row  <- queue.nextUnprocessed(topic, "consumer")
          _    <- queue.commitOffset(topic, "consumer", row.get.id)
          _    <- queue.purge(topic, java.time.Duration.ZERO)
          left <- countRows(pool, topic)
        yield left shouldBe 1
      }
    }

    "not purge messages a consumer has not processed" in withContainers { container =>
      withQueue(container) { (pool, queue) =>
        val topic = newTopic
        for
          _    <- queue.ensureConsumer(topic, "a", 0)
          _    <- queue.ensureConsumer(topic, "b", 0)
          _    <- (1 to 2).toList.traverse_(n => publish(pool, queue, topic, n))
          row  <- queue.nextUnprocessed(topic, "a")
          _    <- queue.commitOffset(topic, "a", row.get.id)
          _    <- queue.purge(topic, java.time.Duration.ZERO)
          left <- countRows(pool, topic)
        yield left shouldBe 2
      }
    }
  }

package nl.amony.lib.messagebus

import java.util.UUID
import scala.concurrent.duration.*

import cats.effect.unsafe.implicits.global
import cats.effect.{Deferred, IO, Ref, Resource}
import cats.syntax.all.*
import com.dimafeng.testcontainers.GenericContainer
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import io.circe.Codec
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.testcontainers.containers.wait.strategy.Wait
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import skunk.Session

import nl.amony.{App, DatabaseConfig}

class PostgresMessageTopicSpec extends AnyWordSpecLike with TestContainerForAll with Matchers:

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

  private def topicFor(pool: Resource[IO, Session[IO]], name: String): MessageTopic[Int] =
    given Codec[Int]           = Codec.implied[Int]
    given MessageTopicKey[Int] = MessageTopicKey(name)
    PersistentMessageBus.postgres(pool, MessageBusConfig(retryBackoff = 100.millis)).getTopic[Int]

  /** Registers a consumer group from the start of the log, so a test can publish before the consumer starts. */
  private def registerFromStart(pool: Resource[IO, Session[IO]], name: String, consumerId: String): IO[Unit] =
    MessageQueue(pool).ensureConsumer(name, consumerId, 0)

  private def await(ref: Ref[IO, List[Int]], size: Int): IO[List[Int]] =
    ref.get.flatMap(xs => if xs.size >= size then IO.pure(xs) else IO.sleep(50.millis) *> await(ref, size)).timeout(15.seconds)

  "PostgresMessageTopic" should {

    "process published messages in order" in withContainers { container =>
      App.makeDatabasePool(configForContainer(container)).use { pool =>
        val name  = s"topic-${UUID.randomUUID()}"
        val topic = topicFor(pool, name)

        for
          _         <- registerFromStart(pool, name, "consumer")
          processed <- Ref.of[IO, List[Int]](Nil)
          _         <- topic.processAtLeastOnce("consumer")(n => processed.update(_ :+ n)).compile.drain.background.use { _ =>
                         List(1, 2, 3, 4, 5).traverse_(topic.publish) >> await(processed, 5)
                       }
          result    <- processed.get
        yield result shouldBe List(1, 2, 3, 4, 5)
      }.unsafeRunSync()
    }

    "retry a failing message in place rather than skipping it" in withContainers { container =>
      App.makeDatabasePool(configForContainer(container)).use { pool =>
        val name  = s"topic-${UUID.randomUUID()}"
        val topic = topicFor(pool, name)

        for
          _         <- registerFromStart(pool, name, "consumer")
          processed <- Ref.of[IO, List[Int]](Nil)
          attempts  <- Ref.of[IO, Int](0)
          processor  = (n: Int) =>
                         attempts.modify(a => (a + 1, a)).flatMap { attempt =>
                           if attempt < 2 then IO.raiseError[Unit](new RuntimeException("transient")) else processed.update(_ :+ n)
                         }
          _         <- topic.processAtLeastOnce("consumer")(processor).compile.drain.background.use { _ =>
                         List(1, 2).traverse_(topic.publish) >> await(processed, 2)
                       }
          result    <- processed.get
        yield result shouldBe List(1, 2)
      }.unsafeRunSync()
    }

    "reprocess a message that was not committed before the consumer stopped" in withContainers { container =>
      App.makeDatabasePool(configForContainer(container)).use { pool =>
        val name  = s"topic-${UUID.randomUUID()}"
        val topic = topicFor(pool, name)

        for
          _         <- registerFromStart(pool, name, "consumer")
          read      <- Deferred[IO, Unit]
          processed <- Ref.of[IO, List[Int]](Nil)
          // The first run reads the message, then blocks forever without committing its offset.
          _         <- topic.processAtLeastOnce("consumer")(_ => read.complete(()) >> IO.never).compile.drain.background.use { _ =>
                         topic.publish(1) >> read.get.timeout(15.seconds)
                       }
          // A second run of the same consumer resumes from the uncommitted offset and processes it.
          _         <- topic.processAtLeastOnce("consumer")(n => processed.update(_ :+ n)).compile.drain.background.use { _ =>
                         await(processed, 1)
                       }
          result    <- processed.get
        yield result shouldBe List(1)
      }.unsafeRunSync()
    }
  }

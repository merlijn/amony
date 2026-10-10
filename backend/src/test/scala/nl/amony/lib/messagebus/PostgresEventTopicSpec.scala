package nl.amony.lib.messagebus

import java.util.UUID
import scala.concurrent.duration.*

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import com.dimafeng.testcontainers.GenericContainer
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.testcontainers.containers.wait.strategy.Wait
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer

import nl.amony.{App, DatabaseConfig}

class PostgresEventTopicSpec extends AnyWordSpecLike with TestContainerForAll with Matchers:

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

  given meter: Meter[IO]      = Meter.noop[IO]
  given tracer: Tracer[IO]    = Tracer.noop[IO]
  given PersistenceCodec[Int] = PersistenceCodec.fromCirce
  given EventTopicKey[Int]    = EventTopicKey(s"topic-${UUID.randomUUID()}")

  private def configForContainer(container: GenericContainer): DatabaseConfig =
    DatabaseConfig(
      host     = container.containerIpAddress,
      port     = container.mappedPort(5432),
      database = "test",
      username = "test",
      password = "test",
      poolSize = 4
    )

  private def await(ref: Ref[IO, List[Int]], size: Int): IO[List[Int]] =
    ref.get.flatMap(xs => if xs.size >= size then IO.pure(xs) else IO.sleep(50.millis) *> await(ref, size)).timeout(15.seconds)

  "PostgresEventTopic" should {

    "process published events in order" in withContainers { container =>
      App.makeDatabasePool(configForContainer(container)).use { pool =>
        val topic = PersistentEventBus.postgres(pool).getTopic[Int]

        Ref.of[IO, List[Int]](Nil).flatMap { processed =>
          topic.processAtLeastOnce("test")(n => processed.update(_ :+ n)).compile.drain.background.use { _ =>
            List(1, 2, 3, 4, 5).traverse_(topic.publish) >> await(processed, 5)
          }
        }
      }.map(_ shouldBe List(1, 2, 3, 4, 5)).unsafeRunSync()
    }

    "retry a failing event in place rather than skipping it" in withContainers { container =>
      App.makeDatabasePool(configForContainer(container)).use { pool =>
        val topic = PersistentEventBus.postgres(pool).getTopic[Int]

        for
          processed <- Ref.of[IO, List[Int]](Nil)
          attempts  <- Ref.of[IO, Int](0)
          processor  = (n: Int) =>
                         attempts.modify(a => (a + 1, a)).flatMap { attempt =>
                           if attempt < 2 then IO.raiseError[Unit](new RuntimeException("transient")) else processed.update(_ :+ n)
                         }
          _         <- topic.processAtLeastOnce("test")(processor).compile.drain.background.use { _ =>
                         List(1, 2).traverse_(topic.publish) >> await(processed, 2)
                       }
          result    <- processed.get
        yield result
      }.map(_ shouldBe List(1, 2)).unsafeRunSync()
    }
  }

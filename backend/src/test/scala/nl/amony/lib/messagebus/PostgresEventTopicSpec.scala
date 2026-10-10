package nl.amony.lib.messagebus

import java.util.UUID
import scala.concurrent.duration.*

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import com.dimafeng.testcontainers.GenericContainer
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.testcontainers.containers.wait.strategy.Wait
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import skunk.Session

import nl.amony.DatabaseConfig

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

  private def await(ref: Ref[IO, List[Int]], size: Int): IO[List[Int]] =
    ref.get.flatMap(xs => if xs.size >= size then IO.pure(xs) else IO.sleep(50.millis) *> await(ref, size)).timeout(15.seconds)

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

  "PostgresEventTopic" should {

    "process published events in order" in withContainers { container =>
      makePool(configForContainer(container)).use { pool =>
        val topic = PersistentEventBus.postgres(pool).getTopic[Int]

        Ref.of[IO, List[Int]](Nil).flatMap { processed =>
          topic.processAtLeastOnce("test")(n => processed.update(_ :+ n)).compile.drain.background.use { _ =>
            List(1, 2, 3, 4, 5).traverse_(topic.publish) >> await(processed, 5)
          }
        }
      }.map(_ shouldBe List(1, 2, 3, 4, 5)).unsafeRunSync()
    }

    "retry a failing event in place rather than skipping it" in withContainers { container =>
      makePool(configForContainer(container)).use { pool =>
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

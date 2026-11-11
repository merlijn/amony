package nl.amony.platform.messagebus.impl

import java.time.Duration
import java.util.UUID

import cats.effect.unsafe.implicits.global
import cats.effect.{Deferred, IO, Resource}
import cats.syntax.all.*
import com.dimafeng.testcontainers.GenericContainer
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import io.circe.Json
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.testcontainers.containers.wait.strategy.Wait
import org.typelevel.otel4s.metrics.{Meter, MeterProvider}
import org.typelevel.otel4s.trace.{Tracer, TracerProvider}
import skunk.codec.all.*
import skunk.implicits.*
import skunk.{Session, *}

import nl.amony.platform.messagebus.MessageBusTestSupport

class MessageLogSpec extends AnyWordSpecLike with TestContainerForAll with Matchers:

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
  given MeterProvider[IO]  = MeterProvider.noop[IO]
  given TracerProvider[IO] = TracerProvider.noop[IO]

  private val lease: Duration = Duration.ofSeconds(30)

  private def withLog[A](container: GenericContainer)(f: (Resource[IO, Session[IO]], MessageLog) => IO[A]): A =
    MessageBusTestSupport.makeDatabasePool(container).use(pool => f(pool, MessageLog(pool))).unsafeRunSync()

  private def newTopic: String = s"test-${UUID.randomUUID()}"

  private def payload(n: Int): Json = Json.obj("n" -> Json.fromInt(n))

  private def nOf(json: Json): Int = json.hcursor.get[Int]("n").getOrElse(throw new RuntimeException(s"missing 'n' in $json"))

  private def publish(pool: Resource[IO, Session[IO]], log: MessageLog, topic: String, n: Int, partition: Int = 0): IO[Unit] =
    pool.use(session => log.enqueue(session, topic, partition, s"key-$n", payload(n)))

  private def countRows(pool: Resource[IO, Session[IO]], topic: String): IO[Long] =
    pool.use(session => session.prepare(sql"select count(*) from message_log where topic = $varchar".query(int8)).flatMap(_.unique(topic)))

  private def consumerState(pool: Resource[IO, Session[IO]], topic: String, consumerId: String, partition: Int = 0): IO[(Int, Option[String])] =
    pool.use(
      _.prepare(
        sql"select attempts, last_error from message_consumer where topic = $varchar and consumer_id = $varchar and partition = $int4".query(
          int4 *: text.opt
        )
      ).flatMap(_.unique((topic, consumerId, partition)))
    )

  private def readAll(log: MessageLog, topic: String, consumerId: String): IO[List[Int]] =
    log.claimNextUnprocessed(topic, consumerId, lease).flatMap {
      case None      => IO.pure(Nil)
      case Some(row) =>
        log.commitOffset(topic, consumerId, row.partition, row.id).as(nOf(row.payload)).flatMap(n => readAll(log, topic, consumerId).map(n :: _))
    }

  "MessageLog" when {

    "reading messages" should {

      "return them in insertion order for a consumer" in withContainers { container =>
        withLog(container) { (pool, log) =>
          val topic = newTopic
          for
            _  <- log.ensureConsumer(topic, "consumer", 1, 0)
            _  <- (1 to 3).toList.traverse_(n => publish(pool, log, topic, n))
            xs <- readAll(log, topic, "consumer")
          yield xs shouldBe List(1, 2, 3)
        }
      }

      "let independent consumers each read the whole log" in withContainers { container =>
        withLog(container) { (pool, log) =>
          val topic = newTopic
          for
            _ <- log.ensureConsumer(topic, "a", 1, 0)
            _ <- log.ensureConsumer(topic, "b", 1, 0)
            _ <- (1 to 3).toList.traverse_(n => publish(pool, log, topic, n))
            a <- readAll(log, topic, "a")
            b <- readAll(log, topic, "b")
          yield
            a shouldBe List(1, 2, 3)
            b shouldBe List(1, 2, 3)
        }
      }

      "not skip a message whose transaction commits after a later message's transaction" in withContainers { container =>
        withLog(container) { (pool, log) =>
          val topic = newTopic
          for
            _             <- log.ensureConsumer(topic, "consumer", 1, 0)
            tx1Inserted   <- Deferred[IO, Unit]
            tx2Committed  <- Deferred[IO, Unit]
            // Tx 1 obtains the lower sequence ID first, but stays uncommitted while Tx 2 inserts and commits.
            tx1            = pool.use { s1 =>
                               s1.transaction.use { _ =>
                                 log.enqueue(s1, topic, 0, "k1", payload(1)) >>
                                   tx1Inserted.complete(()) >>
                                   tx2Committed.get
                               }
                             }
            tx1Fiber      <- tx1.start
            _             <- tx1Inserted.get
            // Tx 2 inserts message 2 (higher ID) and commits immediately.
            _             <- publish(pool, log, topic, 2)
            // While Tx 1 is still open, claiming must NOT return message 2 (which would advance last_offset past message 1).
            whileTx1Open  <- log.claimNextUnprocessed(topic, "consumer", lease)
            _             <- tx2Committed.complete(())
            _             <- tx1Fiber.joinWithNever
            afterTx1Close <- readAll(log, topic, "consumer")
          yield
            whileTx1Open shouldBe None
            afterTx1Close shouldBe List(1, 2)
        }
      }

      "lease a partition exclusively until committed or expired" in withContainers { container =>
        withLog(container) { (pool, log) =>
          val topic = newTopic
          for
            _      <- log.ensureConsumer(topic, "consumer", 1, 0)
            _      <- (1 to 2).toList.traverse_(n => publish(pool, log, topic, n))
            first  <- log.claimNextUnprocessed(topic, "consumer", lease)
            // A second concurrent worker cannot claim the same partition while the first lease is active.
            second <- log.claimNextUnprocessed(topic, "consumer", lease)
            _      <- log.commitOffset(topic, "consumer", first.get.partition, first.get.id)
            third  <- log.claimNextUnprocessed(topic, "consumer", lease)
          yield
            first.map(r => nOf(r.payload)) shouldBe Some(1)
            second shouldBe None
            third.map(r => nOf(r.payload)) shouldBe Some(2)
        }
      }
    }

    "starting a consumer" should {

      "start a new consumer at the latest message" in withContainers { container =>
        withLog(container) { (pool, log) =>
          val topic = newTopic
          for
            _    <- (1 to 2).toList.traverse_(n => publish(pool, log, topic, n))
            max  <- log.latestId(topic)
            _    <- log.ensureConsumer(topic, "consumer", 1, max)
            none <- log.claimNextUnprocessed(topic, "consumer", lease)
            _    <- publish(pool, log, topic, 3)
            next <- log.claimNextUnprocessed(topic, "consumer", lease)
          yield
            none shouldBe None
            next.map(row => nOf(row.payload)) shouldBe Some(3)
        }
      }
    }

    "recording failures" should {

      "record a failure without advancing the offset and back off the partition lease" in withContainers { container =>
        withLog(container) { (pool, log) =>
          val topic = newTopic
          for
            _     <- log.ensureConsumer(topic, "consumer", 1, 0)
            _     <- publish(pool, log, topic, 1)
            first <- log.claimNextUnprocessed(topic, "consumer", lease)
            _     <- log.recordFailure(topic, "consumer", 0, "boom", Duration.ZERO)
            again <- log.claimNextUnprocessed(topic, "consumer", lease)
            state <- consumerState(pool, topic, "consumer")
          yield
            again.map(_.id) shouldBe first.map(_.id)
            state shouldBe (1, Some("boom"))
        }
      }
    }

    "purging messages" should {

      "purge messages every consumer has processed on that partition" in withContainers { container =>
        withLog(container) { (pool, log) =>
          val topic = newTopic
          for
            _    <- log.ensureConsumer(topic, "consumer", 1, 0)
            _    <- (1 to 2).toList.traverse_(n => publish(pool, log, topic, n))
            row  <- log.claimNextUnprocessed(topic, "consumer", lease)
            _    <- log.commitOffset(topic, "consumer", row.get.partition, row.get.id)
            _    <- log.purge(topic, Duration.ZERO)
            left <- countRows(pool, topic)
          yield left shouldBe 1
        }
      }

      "not purge messages a consumer has not processed" in withContainers { container =>
        withLog(container) { (pool, log) =>
          val topic = newTopic
          for
            _    <- log.ensureConsumer(topic, "a", 1, 0)
            _    <- log.ensureConsumer(topic, "b", 1, 0)
            _    <- (1 to 2).toList.traverse_(n => publish(pool, log, topic, n))
            row  <- log.claimNextUnprocessed(topic, "a", lease)
            _    <- log.commitOffset(topic, "a", row.get.partition, row.get.id)
            _    <- log.purge(topic, Duration.ZERO)
            left <- countRows(pool, topic)
          yield left shouldBe 2
        }
      }
    }
  }

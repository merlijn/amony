package nl.amony.platform.messagebus.impl

import java.time.Duration
import java.util.UUID
import scala.concurrent.duration.*

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
    pool.use(session => log.enqueue(session, topic, partition, "same-key", payload(n)))

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
        log.commitOffset(consumerId, row).flatMap { committed =>
          IO(committed shouldBe true) >> readAll(log, topic, consumerId).map(nOf(row.payload) :: _)
        }
    }

  private def waitUntilBlocked(pool: Resource[IO, Session[IO]], pid: Int): IO[Unit] =
    val blocked         = sql"select cardinality(pg_blocking_pids($int4)) > 0".query(bool)
    def await: IO[Unit] = pool.use(_.prepare(blocked).flatMap(_.unique(pid))).flatMap {
      case true  => IO.unit
      case false => IO.sleep(10.millis) >> await
    }
    await.timeout(10.seconds)

  private def expireLease(pool: Resource[IO, Session[IO]], topic: String, consumerId: String): IO[Unit] =
    val expire = sql"update message_consumer set locked_until = clock_timestamp() where topic = $varchar and consumer_id = $varchar".command
    pool.use(_.prepare(expire).flatMap(_.execute((topic, consumerId)))).void

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

      "serialize same-partition publishes regardless of transaction ID order without blocking other partitions" in withContainers { container =>
        withLog(container) { (pool, log) =>
          val topic = newTopic
          for
            _                <- log.ensureConsumer(topic, "consumer", 2, 0)
            olderPid         <- Deferred[IO, Int]
            firstSent        <- Deferred[IO, Unit]
            allowFirstCommit <- Deferred[IO, Unit]
            older             = pool.use { session =>
                                  session.transaction.use { _ =>
                                    session.unique(sql"select pg_backend_pid() where pg_current_xact_id() is not null".query(int4)).flatMap(olderPid.complete) >>
                                      firstSent.get >> log.enqueue(session, topic, 0, "same-key", payload(2))
                                  }
                                }
            newer             = pool.use { session =>
                                  session.transaction.use { _ =>
                                    log.enqueue(session, topic, 0, "same-key", payload(1)) >> firstSent.complete(()) >> allowFirstCommit.get
                                  }
                                }
            seen             <- older.background.use { olderResult =>
                                  olderPid.get.timeout(10.seconds).flatMap { pid =>
                                    newer.background.use { newerResult =>
                                      for
                                        _      <- waitUntilBlocked(pool, pid)
                                        _      <- publish(pool, log, topic, 3, partition = 1).timeout(10.seconds)
                                        during <- readAll(log, topic, "consumer")
                                        _      <- allowFirstCommit.complete(())
                                        _      <- newerResult.flatMap(_.embedNever)
                                        _      <- olderResult.flatMap(_.embedNever)
                                        after  <- readAll(log, topic, "consumer")
                                      yield (during, after)
                                    }
                                  }
                                }
          yield
            seen._1 shouldBe List(3)
            seen._2 shouldBe List(1, 2)
        }
      }

      "roll back the allocation and message together when the publishing transaction aborts" in withContainers { container =>
        withLog(container) { (pool, log) =>
          val topic = newTopic
          val error = new RuntimeException("abort publish")
          for
            _      <- log.ensureConsumer(topic, "consumer", 1, 0)
            result <- pool.use(session =>
                        session.transaction.use(_ => log.enqueue(session, topic, 0, "same-key", payload(1)) >> IO.raiseError(error))
                      ).attempt
            _      <- publish(pool, log, topic, 2)
            next   <- log.claimNextUnprocessed(topic, "consumer", lease)
          yield
            result shouldBe Left(error)
            next.map(row => (row.offset, nOf(row.payload))) shouldBe Some((1L, 2))
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
            ack    <- log.commitOffset("consumer", first.get)
            third  <- log.claimNextUnprocessed(topic, "consumer", lease)
          yield
            first.map(r => nOf(r.payload)) shouldBe Some(1)
            second shouldBe None
            ack shouldBe true
            third.map(r => nOf(r.payload)) shouldBe Some(2)
        }
      }

      "reject expired claims and fence every stale mutation after takeover" in withContainers { container =>
        withLog(container) { (pool, log) =>
          val topic = newTopic
          val group = "consumer"
          for
            _           <- log.ensureConsumer(topic, group, 1, 0)
            _           <- (1 to 2).toList.traverse_(n => publish(pool, log, topic, n))
            original    <- log.claimNextUnprocessed(topic, group, lease).map(_.get)
            renewed     <- log.renewLease(group, original, lease)
            _           <- expireLease(pool, topic, group)
            expiredAck  <- log.commitOffset(group, original)
            expiredBeat <- log.renewLease(group, original, lease)
            replacement <- log.claimNextUnprocessed(topic, group, lease).map(_.get)
            staleAck    <- log.commitOffset(group, original)
            staleBeat   <- log.renewLease(group, original, lease)
            staleFail   <- log.recordFailure(group, original, "stale failure", Duration.ZERO)
            state       <- consumerState(pool, topic, group)
            whileOwned  <- log.claimNextUnprocessed(topic, group, lease)
            ack         <- log.commitOffset(group, replacement)
            next        <- log.claimNextUnprocessed(topic, group, lease)
          yield
            renewed shouldBe true
            List(expiredAck, expiredBeat, staleAck, staleBeat, staleFail) shouldBe List.fill(5)(false)
            replacement.offset shouldBe original.offset
            replacement.leaseGeneration should be > original.leaseGeneration
            state shouldBe (0, None)
            whileOwned shouldBe None
            ack shouldBe true
            next.map(row => nOf(row.payload)) shouldBe Some(2)
        }
      }
    }

    "starting a consumer" should {

      "start a new consumer at the latest offset of each partition" in withContainers { container =>
        withLog(container) { (pool, log) =>
          val topic = newTopic
          for
            _    <- (1 to 2).toList.traverse_(n => publish(pool, log, topic, n))
            _    <- log.ensureConsumerAtLatest(topic, "consumer", 2)
            none <- log.claimNextUnprocessed(topic, "consumer", lease)
            _    <- publish(pool, log, topic, 3)
            _    <- publish(pool, log, topic, 4, partition = 1)
            next <- readAll(log, topic, "consumer")
          yield
            none shouldBe None
            next shouldBe List(3, 4)
        }
      }
    }

    "recording failures" should {

      "record a failure without advancing the offset and back off the partition lease" in withContainers { container =>
        withLog(container) { (pool, log) =>
          val topic = newTopic
          for
            _      <- log.ensureConsumer(topic, "consumer", 1, 0)
            _      <- publish(pool, log, topic, 1)
            first  <- log.claimNextUnprocessed(topic, "consumer", lease)
            failed <- log.recordFailure("consumer", first.get, "boom", Duration.ZERO)
            again  <- log.claimNextUnprocessed(topic, "consumer", lease)
            state  <- consumerState(pool, topic, "consumer")
          yield
            failed shouldBe true
            again.map(_.id) shouldBe first.map(_.id)
            state shouldBe (1, Some("boom"))
        }
      }
    }

    "measuring the backlog" should {

      "report the oldest unread age for each consumer even when timestamps are out of offset order" in withContainers { container =>
        withLog(container) { (pool, log) =>
          val topic       = newTopic
          val ageMessages = sql"""
            update message_log
            set created_at = clock_timestamp() - case when partition_offset = 1 then interval '1 minute' else interval '10 minutes' end
            where topic = $varchar
          """.command

          for
            _        <- log.ensureConsumer(topic, "a", 1, 0)
            _        <- log.ensureConsumer(topic, "b", 1, 0)
            _        <- (1 to 2).toList.traverse_(n => publish(pool, log, topic, n))
            _        <- pool.use(_.prepare(ageMessages).flatMap(_.execute(topic)))
            _        <- readAll(log, topic, "a")
            caughtUp <- log.oldestPendingAge(topic, "a")
            lagging  <- log.oldestPendingAge(topic, "b")
          yield
            caughtUp shouldBe None
            lagging.map(_.toMinutes) shouldBe Some(10L)
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
            _    <- log.commitOffset("consumer", row.get)
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
            _    <- log.commitOffset("a", row.get)
            _    <- log.purge(topic, Duration.ZERO)
            left <- countRows(pool, topic)
          yield left shouldBe 2
        }
      }
    }
  }

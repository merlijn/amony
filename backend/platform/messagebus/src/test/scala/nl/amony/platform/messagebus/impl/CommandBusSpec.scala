package nl.amony.platform.messagebus.impl

import java.util.UUID
import scala.concurrent.duration.*

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import com.dimafeng.testcontainers.GenericContainer
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import io.circe.Codec
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.testcontainers.containers.wait.strategy.Wait
import org.typelevel.otel4s.metrics.{Meter, MeterProvider}
import org.typelevel.otel4s.trace.{Tracer, TracerProvider}
import skunk.Session

import nl.amony.platform.messagebus.*

class CommandBusSpec extends AnyWordSpecLike with TestContainerForAll with Matchers:

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

  given Meter[IO]          = Meter.noop[IO]
  given Tracer[IO]         = Tracer.noop[IO]
  given MeterProvider[IO]  = MeterProvider.noop[IO]
  given TracerProvider[IO] = TracerProvider.noop[IO]

  final case class TestCommand(value: Int) derives Codec.AsObject

  private val config = MessageBusConfig(pollInterval = 100.millis, retryBackoff = 100.millis, leaseDuration = 5.seconds)

  private def pool(container: GenericContainer): Resource[IO, Resource[IO, Session[IO]]] =
    MessageBusTestSupport.makeDatabasePool(container)

  private def busFor(pool: Resource[IO, Session[IO]], name: String, maxAttempts: Int = 3): CommandBus[TestCommand] =
    given CommandTopicKey[TestCommand] = CommandTopicKey(name, partitions = 2, maxAttempts = maxAttempts)
    MessageBus.postgres(pool, config).getCommandBus[TestCommand]

  "CommandBus" when {

    "submitting commands" should {

      "make a submitted command observable and run it exactly once" in withContainers { container =>
        pool(container).use { pool =>
          val name = s"commands-${UUID.randomUUID()}"
          val bus  = busFor(pool, name)

          val test =
            for
              executed  <- Ref.of[IO, List[Int]](Nil)
              id        <- bus.submit("key", TestCommand(1))
              queued    <- bus.status(id)
              _          = queued.map(_.state) shouldBe Some(CommandState.Queued)
              consumer  <- bus.processAtLeastOnce("consumer")((_, command) => executed.update(_ :+ command.value)).compile.drain.start
              completed <- bus.awaitCompletion(id, 20.seconds)
              _          = completed.state shouldBe CommandState.Succeeded
              _          = completed.attempts shouldBe 1
              values    <- executed.get
              _          = values shouldBe List(1)
              statuses  <- bus.subscribe(id).compile.toList
              _          = statuses.map(_.state) shouldBe List(CommandState.Succeeded)
              _         <- consumer.cancel
            yield ()

          test.timeout(40.seconds)
        }.unsafeRunSync()
      }

      "roll the command back together with the caller's transaction" in withContainers { container =>
        pool(container).use { pool =>
          val name = s"commands-${UUID.randomUUID()}"
          val bus  = busFor(pool, name)
          val id   = CommandId(UUID.randomUUID().toString)

          val test =
            for
              _      <- pool
                          .use(session =>
                            session.transaction.use(_ => bus.submit(session, id, "key", TestCommand(1)) >> IO.raiseError(new RuntimeException("rollback")))
                          )
                          .attempt
              status <- bus.status(id)
              _       = status shouldBe None
            yield ()

          test.timeout(20.seconds)
        }.unsafeRunSync()
      }
    }

    "processing commands" should {

      "fail a command terminally after the attempt limit and keep the partition moving" in withContainers { container =>
        pool(container).use { pool =>
          val name = s"commands-${UUID.randomUUID()}"
          val bus  = busFor(pool, name, maxAttempts = 2)

          val test =
            for
              attempts  <- Ref.of[IO, Int](0)
              id        <- bus.submit("key", TestCommand(1))
              failing   <- bus
                             .processAtLeastOnce("consumer")((_, _) => attempts.update(_ + 1) >> IO.raiseError(new RuntimeException("boom")))
                             .compile
                             .drain
                             .start
              completed <- bus.awaitCompletion(id, 20.seconds)
              _          = completed.state shouldBe CommandState.Failed
              _          = completed.attempts shouldBe 2
              _          = completed.lastError shouldBe Some("boom")
              tries     <- attempts.get
              _          = tries shouldBe 2
              _         <- failing.cancel

              next      <- bus.submit("key", TestCommand(2))
              processed <- Ref.of[IO, Boolean](false)
              _         <- bus
                             .processAtLeastOnce("consumer")((_, _) => processed.set(true))
                             .compile
                             .drain
                             .start
                             .flatMap(fiber => bus.awaitCompletion(next, 20.seconds).guarantee(fiber.cancel))
              ok        <- processed.get
              _          = ok shouldBe true
            yield ()

          test.timeout(60.seconds)
        }.unsafeRunSync()
      }

      "fail immediately without retrying on a permanent failure" in withContainers { container =>
        pool(container).use { pool =>
          val name = s"commands-${UUID.randomUUID()}"
          val bus  = busFor(pool, name, maxAttempts = 5)

          val test =
            for
              attempts  <- Ref.of[IO, Int](0)
              id        <- bus.submit("key", TestCommand(1))
              consumer  <- bus
                             .processAtLeastOnce("consumer")((_, _) => attempts.update(_ + 1) >> IO.raiseError(new PermanentCommandFailure("nope")))
                             .compile
                             .drain
                             .start
              completed <- bus.awaitCompletion(id, 20.seconds)
              _          = completed.state shouldBe CommandState.Failed
              _          = completed.attempts shouldBe 1
              _          = completed.lastError shouldBe Some("nope")
              tries     <- attempts.get
              _          = tries shouldBe 1
              _         <- consumer.cancel
            yield ()

          test.timeout(40.seconds)
        }.unsafeRunSync()
      }

      "run slow work outside the transaction and commit it atomically" in withContainers { container =>
        pool(container).use { pool =>
          val name = s"commands-${UUID.randomUUID()}"
          val bus  = busFor(pool, name)

          val test =
            for
              worked    <- Ref.of[IO, Boolean](false)
              committed <- Ref.of[IO, Boolean](false)
              id        <- bus.submit("key", TestCommand(7))
              consumer  <- bus
                             .processAtLeastOnce("consumer")((command: TestCommand) => worked.set(true).as(command.value * 2)) {
                               (_, command, result) =>
                                 committed.set(true) >> IO.raiseUnless(result == command.value * 2)(new RuntimeException("unexpected result"))
                             }
                             .compile
                             .drain
                             .start
              completed <- bus.awaitCompletion(id, 20.seconds)
              _          = completed.state shouldBe CommandState.Succeeded
              didWork   <- worked.get
              didCommit <- committed.get
              _          = didWork shouldBe true
              _          = didCommit shouldBe true
              _         <- consumer.cancel
            yield ()

          test.timeout(40.seconds)
        }.unsafeRunSync()
      }

      "dedupe terminal commands and requeue handled failures" in withContainers { container =>
        pool(container).use { pool =>
          val name  = s"commands-${UUID.randomUUID()}"
          val store = CommandStore(pool)
          val id    = CommandId(UUID.randomUUID().toString)

          val test =
            for
              _      <- pool.use(session => session.transaction.use(_ => store.insert(session, name, id, "key")))
              first  <- store.claim(name, id, java.time.Duration.ofMinutes(1))
              _       = first shouldBe Claim.Acquired(1)
              _      <- store.requeue(name, id, "boom")
              second <- store.claim(name, id, java.time.Duration.ofMinutes(1))
              _       = second shouldBe Claim.Acquired(2)
              _      <- pool.use(session => session.transaction.use(_ => store.succeed(session, name, id)))
              again  <- store.claim(name, id, java.time.Duration.ofMinutes(1))
              _       = again shouldBe Claim.Done
              _      <- store.purge(name, java.time.Duration.ZERO)
              gone   <- store.get(name, id)
              _       = gone shouldBe None
            yield ()

          test.timeout(20.seconds)
        }.unsafeRunSync()
      }
    }
  }

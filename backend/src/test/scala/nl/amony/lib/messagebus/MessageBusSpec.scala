package nl.amony.lib.messagebus

import java.util.UUID
import scala.concurrent.duration.*

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import com.dimafeng.testcontainers.GenericContainer
import io.circe.Codec
import liquibase.Contexts
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.testcontainers.containers.wait.strategy.Wait
import org.typelevel.otel4s.metrics.{Meter, MeterProvider}
import org.typelevel.otel4s.trace.{Tracer, TracerProvider}
import skunk.Session

import nl.amony.{App, DatabaseConfig}

/**
 * A rough throughput indication for a single producer / single consumer on one node. Disabled by default; enable it
 * with `AMONY_PERF=true` (optionally `AMONY_PERF_RUNS` and `AMONY_PERF_MESSAGES`), or the equivalent `amony.perf`,
 * `amony.perf.runs` and `amony.perf.messages` system properties. Each run produces `messages` messages one transaction
 * at a time (like the real producer) and then processes them with a no-op handler, so it measures the bus rather than
 * the handler. With a persistent sbt server, pass the system properties through the forked JVM, e.g.
 * `sbt 'set Test/javaOptions += "-Damony.perf=true"; testOnly nl.amony.lib.messagebus.MessageBusPerformanceSpec'`.
 */
class MessageBusSpec extends AnyWordSpecLike with Matchers:

  given meter: Meter[IO]   = Meter.noop[IO]
  given tracer: Tracer[IO] = Tracer.noop[IO]
  given MeterProvider[IO]  = MeterProvider.noop[IO]
  given TracerProvider[IO] = TracerProvider.noop[IO]
  given Codec[Int]         = Codec.implied[Int]

  private def setting(prop: String, env: String): Option[String] = sys.props.get(prop).orElse(sys.env.get(env))

  private val enabled = setting("amony.perf", "AMONY_PERF").contains("true")

  "PersistentMessageBus" should {

    "report single producer / single consumer throughput" in {
      assume(enabled, "performance test disabled; see the class comment for how to run it")

      val runs     = setting("amony.perf.runs", "AMONY_PERF_RUNS").flatMap(_.toIntOption).getOrElse(10)
      val messages = setting("amony.perf.messages", "AMONY_PERF_MESSAGES").flatMap(_.toIntOption).getOrElse(10000)

      val container = GenericContainer(
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

      container.start()
      try
        App.makeDatabasePool(configForContainer(container), new Contexts("message-log")).use { pool =>
          (1 to runs).toList.traverse_(run(pool, _, messages, runs))
        }.unsafeRunSync()
      finally container.stop()
    }
  }

  private def configForContainer(container: GenericContainer): DatabaseConfig =
    DatabaseConfig(
      host     = container.containerIpAddress,
      port     = container.mappedPort(5432),
      database = "test",
      username = "test",
      password = "test",
      poolSize = 4
    )

  private def run(pool: Resource[IO, Session[IO]], runNumber: Int, messages: Int, runs: Int): IO[Unit] =
    val name                   = s"perf-${UUID.randomUUID()}"
    given MessageTopicKey[Int] = MessageTopicKey(name)
    val topic                  = PersistentMessageBus.postgres(pool).getTopic[Int]

    for
      _            <- MessageLog(pool).ensureConsumer(name, "consumer", 0)
      produceStart <- IO.monotonic
      _            <- (1 to messages).toList.traverse_(topic.publish)
      produceEnd   <- IO.monotonic
      processed    <- Ref.of[IO, Int](0)
      processStart <- IO.monotonic
      _            <- topic.processAtLeastOnce("consumer")(_ => processed.update(_ + 1)).compile.drain.background.use { _ =>
                        await(processed, messages)
                      }
      processEnd   <- IO.monotonic
      produceMs     = (produceEnd - produceStart).toMillis.toDouble
      processMs     = (processEnd - processStart).toMillis.toDouble
      _            <- IO.println(
                        f"run $runNumber%2d/$runs%d | produce ${produceMs / 1000}%7.2fs (${messages * 1000.0 / produceMs}%8.0f msg/s) | " +
                          f"process ${processMs / 1000}%7.2fs (${messages * 1000.0 / processMs}%8.0f msg/s)"
                      )
    yield ()

  private def await(ref: Ref[IO, Int], target: Int): IO[Unit] =
    ref.get.flatMap(n => if n >= target then IO.unit else IO.sleep(10.millis) *> await(ref, target)).timeout(300.seconds)

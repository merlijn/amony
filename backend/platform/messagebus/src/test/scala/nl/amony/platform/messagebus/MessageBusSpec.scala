package nl.amony.platform.messagebus

import java.util.UUID
import scala.concurrent.duration.*
import scala.util.Random

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import com.dimafeng.testcontainers.GenericContainer
import io.circe.Codec
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.testcontainers.containers.wait.strategy.Wait
import org.typelevel.otel4s.metrics.{Meter, MeterProvider}
import org.typelevel.otel4s.trace.{Tracer, TracerProvider}
import skunk.Session
import skunk.codec.all.*
import skunk.implicits.*

import nl.amony.platform.messagebus.impl.MessageLog

/**
 * Local throughput benchmarks, disabled by default. Enable with `AMONY_PERF=true` (optionally `AMONY_PERF_RUNS` and
 * `AMONY_PERF_MESSAGES` for the single-producer test's message count), or the equivalent `amony.perf`, `amony.perf.runs`
 * and `amony.perf.messages` system properties. With a persistent sbt server, pass properties to the forked JVM, e.g.
 * `sbt 'set messagebus / Test / javaOptions += "-Damony.perf=true"; messagebus/testOnly nl.amony.platform.messagebus.MessageBusSpec'`.
 */
class MessageBusSpec extends AnyWordSpecLike with Matchers:

  given meter: Meter[IO]   = Meter.noop[IO]
  given tracer: Tracer[IO] = Tracer.noop[IO]
  given MeterProvider[IO]  = MeterProvider.noop[IO]
  given TracerProvider[IO] = TracerProvider.noop[IO]
  given Codec[Int]         = Codec.implied[Int]

  private def setting(prop: String, env: String): Option[String] = sys.props.get(prop).orElse(sys.env.get(env))

  private val enabled = setting("amony.perf", "AMONY_PERF").contains("true")
  private val runs    = setting("amony.perf.runs", "AMONY_PERF_RUNS").flatMap(_.toIntOption).getOrElse(3)

  "MessageBus" should {

    "report single producer / single consumer throughput" in {
      assume(enabled, "performance test disabled; see the class comment for how to run it")

      val messages = setting("amony.perf.messages", "AMONY_PERF_MESSAGES").flatMap(_.toIntOption).getOrElse(2000)
      withPool(pool => (1 to runs).toList.traverse_(run(pool, _, messages, runs)))
    }

    "report throughput for five concurrent producers across three partitions" in {
      assume(enabled, "performance test disabled; see the class comment for how to run it")

      withPool(pool => (1 to runs).toList.traverse_(runConcurrentProducers(pool, _)))
    }
  }

  private def withPool[A](test: Resource[IO, Session[IO]] => IO[A]): A =
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
    try MessageBusTestSupport.makeDatabasePool(container).use(test).unsafeRunSync()
    finally container.stop()

  private def runConcurrentProducers(pool: Resource[IO, Session[IO]], runNumber: Int): IO[Unit] =
    val producers           = 5
    val messagesPerProducer = 500
    val partitions          = 3
    val name                = s"perf-${UUID.randomUUID()}"
    val key                 = MessageTopicKey[Int](name, _.toString, partitions)
    val topic               = MessageBus.postgres(pool).getTopicForKey(key)
    val keysByPartition     = (0 until partitions).map(partition => Iterator.from(0).find(key.partitionFor(_)._2 == partition).get)
    val random              = new Random(42L)
    val workloads           = List.fill(producers)(List.fill(messagesPerProducer)(keysByPartition(random.nextInt(partitions))))
    val total               = producers * messagesPerProducer
    val expected            = workloads.flatten.groupMapReduce(key.partitionFor(_)._2)(_ => 1L)(_ + _).toList.sortBy(_._1)
    val counts              = sql"select partition, count(*) from message_log where topic = $varchar group by partition order by partition".query(int4 *: int8)

    for
      start  <- IO.monotonic
      _      <- workloads.parTraverse_(_.traverse_(topic.publish))
      end    <- IO.monotonic
      actual <- pool.use(_.prepare(counts).flatMap(_.stream(name, partitions).compile.toList))
      _      <- IO(actual shouldBe expected)
      seconds = (end - start).toNanos / 1e9
      _      <- IO.println(f"concurrent producers run $runNumber/$runs | publish $total messages in $seconds%.2fs (${total / seconds}%.0f msg/s) | " +
                  actual.map { case (partition, count) => s"p$partition=$count" }.mkString(", "))
    yield ()

  private def run(pool: Resource[IO, Session[IO]], runNumber: Int, messages: Int, runs: Int): IO[Unit] =
    val name                   = s"perf-${UUID.randomUUID()}"
    given MessageTopicKey[Int] = MessageTopicKey(name, _.toString)
    val topic                  = MessageBus.postgres(pool).getTopic[Int]

    for
      _            <- MessageLog(pool).ensureConsumer(name, "consumer", MessageTopicKey.DefaultPartitions, 0)
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

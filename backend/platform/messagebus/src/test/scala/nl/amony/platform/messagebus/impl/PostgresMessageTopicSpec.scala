package nl.amony.platform.messagebus.impl

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
import org.typelevel.otel4s.metrics.{Meter, MeterProvider}
import org.typelevel.otel4s.trace.{Tracer, TracerProvider}
import skunk.Session

import nl.amony.platform.messagebus.*

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
  given MeterProvider[IO]  = MeterProvider.noop[IO]
  given TracerProvider[IO] = TracerProvider.noop[IO]

  final case class KeyedMessage(key: String, seq: Int) derives Codec.AsObject

  private def pool(container: GenericContainer): Resource[IO, Resource[IO, Session[IO]]] =
    MessageBusTestSupport.makeDatabasePool(container)

  private def topicFor(
    pool: Resource[IO, Session[IO]],
    name: String,
    partitions: Int          = 1,
    config: MessageBusConfig = MessageBusConfig(pollInterval = 200.millis, retryBackoff = 100.millis, leaseDuration = 5.seconds)
  ): MessageTopic[Int] =
    given Codec[Int]           = Codec.implied[Int]
    given MessageTopicKey[Int] = MessageTopicKey(name, _.toString, partitions)
    PersistentMessageBus.postgres(pool, config).getTopic[Int]

  private def keyedTopicFor(
    pool: Resource[IO, Session[IO]],
    name: String,
    partitions: Int          = 8,
    config: MessageBusConfig = MessageBusConfig(pollInterval = 100.millis, retryBackoff = 100.millis, leaseDuration = 5.seconds)
  ): MessageTopic[KeyedMessage] =
    given MessageTopicKey[KeyedMessage] = MessageTopicKey(name, _.key, partitions)
    PersistentMessageBus.postgres(pool, config).getTopic[KeyedMessage]

  /** Registers a consumer group from the start of the log across all partitions, so a test can publish before the consumer starts. */
  private def registerFromStart(pool: Resource[IO, Session[IO]], name: String, consumerId: String, partitions: Int = 1): IO[Unit] =
    MessageLog(pool).ensureConsumer(name, consumerId, partitions, 0)

  private def await[A](ref: Ref[IO, List[A]], size: Int): IO[List[A]] =
    ref.get.flatMap(xs => if xs.size >= size then IO.pure(xs) else IO.sleep(50.millis) *> await(ref, size)).timeout(15.seconds)

  "PostgresMessageTopic" when {

    "delivering messages" should {

      "process published messages in order" in withContainers { container =>
        pool(container).use { pool =>
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

      "deliver via NOTIFY without waiting for the poll" in withContainers { container =>
        pool(container).use { pool =>
          val name  = s"topic-${UUID.randomUUID()}"
          // A one-hour poll interval, so only the NOTIFY path can deliver the message in time.
          val topic = topicFor(pool, name, config = MessageBusConfig(pollInterval = 1.hour, retryBackoff = 100.millis))

          for
            _         <- registerFromStart(pool, name, "consumer")
            processed <- Ref.of[IO, List[Int]](Nil)
            _         <- topic.processAtLeastOnce("consumer")(n => processed.update(_ :+ n)).compile.drain.background.use { _ =>
                           // Let the initial poll and the LISTEN subscription settle before publishing.
                           IO.sleep(500.millis) >> topic.publish(1) >> await(processed, 1)
                         }
            result    <- processed.get
          yield result shouldBe List(1)
        }.unsafeRunSync()
      }
    }

    "using consumer groups" should {

      "consume the whole log independently per consumer group" in withContainers { container =>
        pool(container).use { pool =>
          val name  = s"topic-${UUID.randomUUID()}"
          val topic = topicFor(pool, name)

          for
            _        <- registerFromStart(pool, name, "a")
            _        <- registerFromStart(pool, name, "b")
            seenA    <- Ref.of[IO, List[Int]](Nil)
            seenB    <- Ref.of[IO, List[Int]](Nil)
            consumers = (
                          topic.processAtLeastOnce("a")(n => seenA.update(_ :+ n)).compile.drain,
                          topic.processAtLeastOnce("b")(n => seenB.update(_ :+ n)).compile.drain
                        )
            _        <- consumers.parTupled.background.use { _ =>
                          List(1, 2, 3).traverse_(topic.publish) >> (await(seenA, 3), await(seenB, 3)).parTupled.void
                        }
            a        <- seenA.get
            b        <- seenB.get
          yield
            a shouldBe List(1, 2, 3)
            b shouldBe List(1, 2, 3)
        }.unsafeRunSync()
      }
    }

    "running multiple nodes in the same consumer group" should {

      "process each message once while preserving per-key ordering across concurrent nodes" in withContainers { container =>
        MessageBusTestSupport.makeDatabasePool(container, poolSize = 12).use { pool =>
          val name       = s"topic-${UUID.randomUUID()}"
          val partitions = 8
          val topic      = keyedTopicFor(pool, name, partitions)

          val keys     = (1 to 10).map(k => s"resource-$k").toList
          val messages = (1 to 5).toList.flatMap(seq => keys.map(k => KeyedMessage(k, seq)))

          for
            _          <- registerFromStart(pool, name, "cluster-group", partitions)
            processed  <- Ref.of[IO, List[KeyedMessage]](Nil)
            inFlight   <- Ref.of[IO, Set[String]](Set.empty)
            overlap    <- Ref.of[IO, Boolean](false)
            processor   = (msg: KeyedMessage) =>
                            for
                              alreadyInFlight <- inFlight.modify(active => (active + msg.key, active.contains(msg.key)))
                              _               <- IO.whenA(alreadyInFlight)(overlap.set(true))
                              _               <- IO.sleep(5.millis)
                              _               <- processed.update(_ :+ msg)
                              _               <- inFlight.update(_ - msg.key)
                            yield ()
            // Run 4 concurrent consumer instances with the SAME consumerId ("cluster-group"), simulating 4 backend nodes.
            nodes       = List.fill(4)(topic.processAtLeastOnce("cluster-group")(processor).compile.drain).parSequence_
            _          <- nodes.background.use { _ =>
                            messages.traverse_(topic.publish) >> await(processed, messages.size)
                          }
            result     <- processed.get
            overlapped <- overlap.get
          yield
            overlapped shouldBe false
            result.size shouldBe messages.size
            // Every key's messages must have been processed strictly in sequence 1..5.
            keys.foreach { key =>
              result.filter(_.key == key).map(_.seq) shouldBe List(1, 2, 3, 4, 5)
            }
        }.unsafeRunSync()
      }

      "drain multiple partitions concurrently on a single node when parallelism > 1" in withContainers { container =>
        MessageBusTestSupport.makeDatabasePool(container, poolSize = 10).use { pool =>
          val name       = s"topic-${UUID.randomUUID()}"
          val partitions = 8
          val topic      = keyedTopicFor(pool, name, partitions)

          // Pick 4 keys that each hash to a distinct partition.
          val topicKey     = MessageTopicKey[KeyedMessage](name, _.key, partitions)
          val distinctKeys = Iterator
            .from(1)
            .map(i => s"k-$i")
            .scanLeft(List.empty[(String, Int)]) { (acc, candidate) =>
              val p = topicKey.partitionFor(KeyedMessage(candidate, 1))._2
              if acc.exists(_._2 == p) then acc else acc :+ (candidate -> p)
            }
            .find(_.size == 4)
            .get
            .map(_._1)

          for
            _         <- registerFromStart(pool, name, "single-node-parallel", partitions)
            active    <- Ref.of[IO, Int](0)
            maxActive <- Ref.of[IO, Int](0)
            allActive <- Deferred[IO, Unit]
            processed <- Ref.of[IO, List[KeyedMessage]](Nil)
            processor  = (msg: KeyedMessage) =>
                           active.updateAndGet(_ + 1).flatMap { current =>
                             maxActive.update(_ max current) >>
                               IO.whenA(current >= 4)(allActive.complete(()).void) >>
                               allActive.get.timeout(5.seconds) >>
                               processed.update(_ :+ msg) >>
                               active.update(_ - 1)
                           }
            _         <- topic.processAtLeastOnce("single-node-parallel", parallelism = Some(4))(processor).compile.drain.background.use { _ =>
                           distinctKeys.traverse_(k => topic.publish(KeyedMessage(k, 1))) >> await(processed, 4)
                         }
            peak      <- maxActive.get
          yield peak shouldBe 4
        }.unsafeRunSync()
      }
    }

    "handling failures" should {

      "retry a failing message in place rather than skipping it" in withContainers { container =>
        pool(container).use { pool =>
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

      "not block other partitions when one partition is backing off on failure" in withContainers { container =>
        pool(container).use { pool =>
          val name  = s"topic-${UUID.randomUUID()}"
          // Long retryBackoff (10s) on the failing partition so it cannot recover during the test window.
          val topic = keyedTopicFor(
            pool,
            name,
            partitions = 8,
            config     = MessageBusConfig(pollInterval = 100.millis, retryBackoff = 10.seconds, leaseDuration = 5.seconds)
          )

          val keyA = "key-a"
          // Pick a second key guaranteed to land on a different partition than keyA.
          val keyB = Iterator.from(1).map(i => s"key-b-$i").find { candidate =>
            val key = MessageTopicKey[KeyedMessage](name, _.key, 8)
            key.partitionFor(KeyedMessage(candidate, 1))._2 != key.partitionFor(KeyedMessage(keyA, 1))._2
          }.get

          for
            _         <- registerFromStart(pool, name, "consumer", partitions = 8)
            processed <- Ref.of[IO, List[KeyedMessage]](Nil)
            processor  = (msg: KeyedMessage) =>
                           if msg.key == keyA then IO.raiseError(new RuntimeException("broken key A"))
                           else processed.update(_ :+ msg)
            _         <- topic.processAtLeastOnce("consumer")(processor).compile.drain.background.use { _ =>
                           topic.publish(KeyedMessage(keyA, 1)) >>
                             topic.publish(KeyedMessage(keyB, 1)) >>
                             await(processed, 1)
                         }
            result    <- processed.get
          yield result shouldBe List(KeyedMessage(keyB, 1))
        }.unsafeRunSync()
      }

      "reprocess a message after lease expiry when a consumer node stops before committing" in withContainers { container =>
        pool(container).use { pool =>
          val name   = s"topic-${UUID.randomUUID()}"
          // Short lease so the second node can take over quickly after the first node cancels.
          val config = MessageBusConfig(pollInterval = 100.millis, retryBackoff = 100.millis, leaseDuration = 300.millis)
          val topic  = topicFor(pool, name, config = config)

          for
            _         <- registerFromStart(pool, name, "consumer")
            read      <- Deferred[IO, Unit]
            processed <- Ref.of[IO, List[Int]](Nil)
            // The first node claims the message, then blocks forever without committing its offset.
            _         <- topic.processAtLeastOnce("consumer")(_ => read.complete(()) >> IO.never).compile.drain.background.use { _ =>
                           topic.publish(1) >> read.get.timeout(15.seconds)
                         }
            // A second node of the same consumer resumes once the short lease expires and processes it.
            _         <- topic.processAtLeastOnce("consumer")(n => processed.update(_ :+ n)).compile.drain.background.use { _ =>
                           await(processed, 1)
                         }
            result    <- processed.get
          yield result shouldBe List(1)
        }.unsafeRunSync()
      }
    }
  }

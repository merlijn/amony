package nl.amony.platform.messagebus.impl

import java.nio.charset.StandardCharsets.UTF_8
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
import skunk.codec.all.*
import skunk.implicits.*

import nl.amony.platform.messagebus.*

class MessageTopicImplSpec extends AnyWordSpecLike with TestContainerForAll with Matchers:

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
    MessageBus.postgres(pool, config).getTopic[Int]

  private def keyedTopicFor(
    pool: Resource[IO, Session[IO]],
    name: String,
    partitions: Int          = 8,
    config: MessageBusConfig = MessageBusConfig(pollInterval = 100.millis, retryBackoff = 100.millis, leaseDuration = 5.seconds)
  ): MessageTopic[KeyedMessage] =
    given MessageTopicKey[KeyedMessage] = MessageTopicKey(name, _.key, partitions)
    MessageBus.postgres(pool, config).getTopic[KeyedMessage]

  /** Registers a consumer group from the start of the log across all partitions, so a test can publish before the consumer starts. */
  private def registerFromStart(pool: Resource[IO, Session[IO]], name: String, consumerId: String, partitions: Int = 1): IO[Unit] =
    MessageLog(pool).ensureConsumer(name, consumerId, partitions, 0)

  private def await[A](ref: Ref[IO, List[A]], size: Int): IO[List[A]] =
    ref.get.flatMap(xs => if xs.size >= size then IO.pure(xs) else IO.sleep(50.millis) *> await(ref, size)).timeout(15.seconds)

  private def awaitListener(pool: Resource[IO, Session[IO]], topic: String, previous: Option[Int] = None): IO[Int] =
    val listen = s"LISTEN ${MessageTopicImpl.channelIdentifier(topic).sql}"
    val find   = sql"select pid from pg_stat_activity where query = $varchar and state = 'idle'".query(int4)

    def waitForListener: IO[Int] = pool.use(_.prepare(find).flatMap(_.option(listen))).flatMap {
      case Some(pid) if !previous.contains(pid) => IO.pure(pid)
      case _                                    => IO.sleep(50.millis) >> waitForListener
    }

    waitForListener.timeout(15.seconds)

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

      "reconnect a terminated notification listener without interrupting processing" in withContainers { container =>
        pool(container).use { pool =>
          val name      = s"topic-${UUID.randomUUID()}"
          val topic     = topicFor(pool, name)
          val terminate = sql"select pg_terminate_backend($int4)".query(bool)

          for
            _         <- registerFromStart(pool, name, "consumer")
            started   <- Deferred[IO, Unit]
            release   <- Deferred[IO, Unit]
            cancelled <- Ref.of[IO, Boolean](false)
            seen      <- Ref.of[IO, List[Int]](Nil)
            processor  = (n: Int) =>
                           (IO.whenA(n == 1)(started.complete(()).void >> release.get) >> seen.update(_ :+ n)).onCancel(cancelled.set(true))
            result    <- topic.processAtLeastOnce("consumer")(processor).compile.drain.background.use { _ =>
                           for
                             original    <- awaitListener(pool, name)
                             _           <- pool.use(session => MessageLog(pool).enqueue(session, name, 0, "key", "1".getBytes(UTF_8)))
                             _           <- started.get.timeout(15.seconds)
                             terminated  <- pool.use(_.prepare(terminate).flatMap(_.unique(original)))
                             _           <- IO(terminated shouldBe true)
                             _           <- awaitListener(pool, name, Some(original))
                             interrupted <- cancelled.get
                             _           <- release.complete(())
                             _           <- topic.publish(2)
                             values      <- await(seen, 2)
                           yield (interrupted, values)
                         }
          yield result shouldBe (false, List(1, 2))
        }.unsafeRunSync()
      }

      "restart the consumer after a database error acknowledging a message" in withContainers { container =>
        pool(container).use { pool =>
          val name      = s"topic-${UUID.randomUUID()}"
          val group     = "restart-test"
          val config    = MessageBusConfig(pollInterval = 100.millis, leaseDuration = 600.millis)
          val topic     = topicFor(pool, name, config = config)
          val install   = pool.use { session =>
            session.execute(sql"create sequence message_bus_test_ack_failure".command) >>
              session.execute(sql"""
                create function message_bus_test_fail_ack() returns trigger language plpgsql as '
                begin
                  if new.last_offset > old.last_offset then
                    if nextval(''message_bus_test_ack_failure'') = 1 then
                      raise exception ''test acknowledgement failure'';
                    end if;
                  end if;
                  return new;
                end;
                '
              """.command) >>
              session.execute(sql"""
                create trigger message_bus_test_ack before update on message_consumer
                for each row when (old.consumer_id = 'restart-test') execute function message_bus_test_fail_ack()
              """.command).void
          }
          val uninstall = pool.use { session =>
            session.execute(sql"drop trigger if exists message_bus_test_ack on message_consumer".command) >>
              session.execute(sql"drop function if exists message_bus_test_fail_ack()".command) >>
              session.execute(sql"drop sequence if exists message_bus_test_ack_failure".command).void
          }

          (install >> (for
            _         <- registerFromStart(pool, name, group)
            processed <- Ref.of[IO, List[Int]](Nil)
            result    <- topic.processAtLeastOnce(group)(n => processed.update(_ :+ n)).compile.drain.background.use { _ =>
                           topic.publish(1) >> topic.publish(2) >> await(processed, 3)
                         }
          yield result shouldBe List(1, 1, 2))).guarantee(uninstall)
        }.unsafeRunSync()
      }

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

      "cancel a running processor when a renewal discovers that its claim is no longer valid" in withContainers { container =>
        pool(container).use { pool =>
          val name  = s"topic-${UUID.randomUUID()}"
          val group = "consumer"
          val topic = topicFor(pool, name)
          val fence = sql"""
            update message_consumer set lease_generation = lease_generation + 1, locked_until = clock_timestamp() + interval '1 minute'
            where topic = $varchar and consumer_id = $varchar
          """.command

          for
            _         <- registerFromStart(pool, name, group)
            started   <- Deferred[IO, Unit]
            cancelled <- Deferred[IO, Unit]
            processor  = (_: Int) => (started.complete(()) >> IO.never[Unit]).onCancel(cancelled.complete(()).void)
            _         <- topic.processAtLeastOnce(group)(processor).compile.drain.background.use { _ =>
                           (topic.publish(1) >> started.get >>
                             pool.use(_.prepare(fence).flatMap(_.execute((name, group)))) >> cancelled.get).timeout(15.seconds)
                         }
          yield succeed
        }.unsafeRunSync()
      }

      "keep running a processor across a transient database error during lease renewal" in withContainers { container =>
        pool(container).use { pool =>
          val name      = s"topic-${UUID.randomUUID()}"
          val group     = s"renew-transient-${UUID.randomUUID()}"
          val seqName   = s"msg_bus_test_renew_${UUID.randomUUID().toString.replace("-", "_")}"
          val fnName    = s"${seqName}_fn"
          val trigName  = s"${seqName}_trig"
          val config    = MessageBusConfig(pollInterval = 100.millis, leaseDuration = 1.second)
          val topic     = topicFor(pool, name, config = config)
          val install   = pool.use { session =>
            session.execute(sql"create sequence #$seqName".command) >>
              session.execute(sql"""
                create function #$fnName() returns trigger language plpgsql as '
                begin
                  if new.lease_generation = old.lease_generation and new.last_offset = old.last_offset then
                    if nextval(''#$seqName'') = 1 then
                      raise exception ''transient renewal failure'';
                    end if;
                  end if;
                  return new;
                end;
                '
              """.command) >>
              session.execute(sql"""
                create trigger #$trigName before update on message_consumer
                for each row when (old.consumer_id = '#$group') execute function #$fnName()
              """.command).void
          }
          val uninstall = pool.use { session =>
            session.execute(sql"drop trigger if exists #$trigName on message_consumer".command) >>
              session.execute(sql"drop function if exists #$fnName()".command) >>
              session.execute(sql"drop sequence if exists #$seqName".command).void
          }

          def awaitRenewals(target: Long): IO[Unit] =
            pool
              .use(_.unique(sql"select last_value, is_called from #$seqName".query(int8 *: bool)))
              .flatMap {
                case (v, true) if v >= target => IO.unit
                case _                        => IO.sleep(50.millis) >> awaitRenewals(target)
              }
              .timeout(15.seconds)

          (install >> (for
            _         <- registerFromStart(pool, name, group)
            started   <- Deferred[IO, Unit]
            release   <- Deferred[IO, Unit]
            cancelled <- Ref.of[IO, Boolean](false)
            processed <- Ref.of[IO, List[Int]](Nil)
            processor  = (n: Int) =>
                           (started.complete(()) >> release.get >> processed.update(_ :+ n)).onCancel(cancelled.set(true))
            result    <- topic.processAtLeastOnce(group)(processor).compile.drain.background.use { _ =>
                           for
                             _           <- topic.publish(1)
                             _           <- started.get.timeout(15.seconds)
                             _           <- awaitRenewals(2)
                             interrupted <- cancelled.get
                             _           <- release.complete(())
                             values      <- await(processed, 1)
                           yield (interrupted, values)
                         }
          yield result shouldBe (false, List(1)))).guarantee(uninstall)
        }.unsafeRunSync()
      }

      "cancel a running processor when lease renewal fails past the lease duration" in withContainers { container =>
        pool(container).use { pool =>
          val name      = s"topic-${UUID.randomUUID()}"
          val group     = s"renew-expired-${UUID.randomUUID()}"
          val fnName    = s"msg_bus_test_renew_fail_${UUID.randomUUID().toString.replace("-", "_")}"
          val trigName  = s"${fnName}_trig"
          val config    = MessageBusConfig(pollInterval = 100.millis, leaseDuration = 400.millis)
          val topic     = topicFor(pool, name, config = config)
          val install   = pool.use { session =>
            session.execute(sql"""
              create function #$fnName() returns trigger language plpgsql as '
              begin
                if new.lease_generation = old.lease_generation and new.last_offset = old.last_offset then
                  raise exception ''persistent renewal failure'';
                end if;
                return new;
              end;
              '
            """.command) >>
              session.execute(sql"""
                create trigger #$trigName before update on message_consumer
                for each row when (old.consumer_id = '#$group') execute function #$fnName()
              """.command).void
          }
          val uninstall = pool.use { session =>
            session.execute(sql"drop trigger if exists #$trigName on message_consumer".command) >>
              session.execute(sql"drop function if exists #$fnName()".command).void
          }

          (install >> (for
            _         <- registerFromStart(pool, name, group)
            started   <- Deferred[IO, Unit]
            cancelled <- Deferred[IO, Unit]
            processor  = (_: Int) => (started.complete(()) >> IO.never[Unit]).onCancel(cancelled.complete(()).void)
            _         <- topic.processAtLeastOnce(group)(processor).compile.drain.background.use { _ =>
                           (topic.publish(1) >> started.get >> cancelled.get).timeout(15.seconds)
                         }
          yield succeed)).guarantee(uninstall)
        }.unsafeRunSync()
      }

      "not cancel other partition workers when one worker hits a database error" in withContainers { container =>
        pool(container).use { pool =>
          val name     = s"topic-${UUID.randomUUID()}"
          val group    = s"isolated-worker-${UUID.randomUUID()}"
          val seqName  = s"msg_bus_test_iso_${UUID.randomUUID().toString.replace("-", "_")}"
          val fnName   = s"${seqName}_fn"
          val trigName = s"${seqName}_trig"
          val config   = MessageBusConfig(pollInterval = 100.millis, leaseDuration = 2.seconds)
          val topic    = keyedTopicFor(pool, name, partitions = 8, config = config)

          val topicKey  = MessageTopicKey[KeyedMessage](name, _.key, 8)
          val keyA      = "key-a"
          val partA     = topicKey.partitionFor(KeyedMessage(keyA, 1))._2
          val keyB      = Iterator.from(1).map(i => s"key-b-$i").find { candidate =>
            topicKey.partitionFor(KeyedMessage(candidate, 1))._2 != partA
          }.get
          val install   = pool.use { session =>
            session.execute(sql"create sequence #$seqName".command) >>
              session.execute(sql"""
                create function #$fnName() returns trigger language plpgsql as '
                begin
                  if new.last_offset > old.last_offset and old.partition = #${partA.toString} then
                    if nextval(''#$seqName'') = 1 then
                      raise exception ''partition A ack failure'';
                    end if;
                  end if;
                  return new;
                end;
                '
              """.command) >>
              session.execute(sql"""
                create trigger #$trigName before update on message_consumer
                for each row when (old.consumer_id = '#$group') execute function #$fnName()
              """.command).void
          }
          val uninstall = pool.use { session =>
            session.execute(sql"drop trigger if exists #$trigName on message_consumer".command) >>
              session.execute(sql"drop function if exists #$fnName()".command) >>
              session.execute(sql"drop sequence if exists #$seqName".command).void
          }

          def awaitAckFailure: IO[Unit] =
            pool
              .use(_.unique(sql"select last_value, is_called from #$seqName".query(int8 *: bool)))
              .flatMap {
                case (v, true) if v >= 1 => IO.unit
                case _                   => IO.sleep(50.millis) >> awaitAckFailure
              }
              .timeout(15.seconds)

          (install >> (for
            _          <- registerFromStart(pool, name, group, partitions = 8)
            bStarted   <- Deferred[IO, Unit]
            releaseB   <- Deferred[IO, Unit]
            bCancelled <- Ref.of[IO, Boolean](false)
            processed  <- Ref.of[IO, List[KeyedMessage]](Nil)
            processor   = (msg: KeyedMessage) =>
                            if msg.key == keyB then
                              (bStarted.complete(()) >> releaseB.get >> processed.update(_ :+ msg)).onCancel(bCancelled.set(true))
                            else bStarted.get
            result     <- topic.processAtLeastOnce(group, parallelism = Some(2))(processor).compile.drain.background.use { _ =>
                            for
                              _           <- topic.publish(KeyedMessage(keyB, 1))
                              _           <- topic.publish(KeyedMessage(keyA, 1))
                              _           <- bStarted.get.timeout(15.seconds)
                              _           <- awaitAckFailure
                              _           <- IO.sleep(100.millis)
                              interrupted <- bCancelled.get
                              _           <- releaseB.complete(())
                              values      <- await(processed, 1)
                            yield (interrupted, values)
                          }
          yield result shouldBe (false, List(KeyedMessage(keyB, 1))))).guarantee(uninstall)
        }.unsafeRunSync()
      }

      "publish a batch atomically while preserving per-key order" in withContainers { container =>
        pool(container).use { pool =>
          val name     = s"topic-${UUID.randomUUID()}"
          val topic    = keyedTopicFor(pool, name, partitions = 8)
          val messages = List(
            KeyedMessage("k1", 1),
            KeyedMessage("k2", 1),
            KeyedMessage("k1", 2),
            KeyedMessage("k3", 1),
            KeyedMessage("k2", 2),
            KeyedMessage("k1", 3)
          )

          for
            _         <- registerFromStart(pool, name, "consumer", partitions = 8)
            processed <- Ref.of[IO, List[KeyedMessage]](Nil)
            _         <- topic.processAtLeastOnce("consumer")(m => processed.update(_ :+ m)).compile.drain.background.use { _ =>
                           topic.publishAll(messages) >> await(processed, messages.size)
                         }
            seen      <- processed.get
          yield
            seen.size shouldBe messages.size
            seen.groupBy(_.key).view.mapValues(_.map(_.seq)).toMap shouldBe Map(
              "k1" -> List(1, 2, 3),
              "k2" -> List(1, 2),
              "k3" -> List(1)
            )
        }.unsafeRunSync()
      }
    }
  }

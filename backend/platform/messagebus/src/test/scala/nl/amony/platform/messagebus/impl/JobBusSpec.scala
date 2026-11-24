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

class JobBusSpec extends AnyWordSpecLike with TestContainerForAll with Matchers:

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

  final case class TestJob(value: Int) derives Codec.AsObject

  private val config = MessageBusConfig(pollInterval = 100.millis, retryBackoff = 100.millis, leaseDuration = 5.seconds)

  private def pool(container: GenericContainer): Resource[IO, Resource[IO, Session[IO]]] =
    MessageBusTestSupport.makeDatabasePool(container)

  private def busFor(pool: Resource[IO, Session[IO]], name: String, maxAttempts: Int = 3): JobBus[TestJob] =
    given JobTopicKey[TestJob] = JobTopicKey(name, partitions = 2, maxAttempts = maxAttempts)
    MessageBus.postgres(pool, config).getJobBus[TestJob]

  "JobBus" when {

    "submitting jobs" should {

      "make a submitted job observable and run it exactly once" in withContainers { container =>
        pool(container).use { pool =>
          val name = s"jobs-${UUID.randomUUID()}"
          val bus  = busFor(pool, name)

          val test =
            for
              executed  <- Ref.of[IO, List[Int]](Nil)
              id        <- bus.submit("key", TestJob(1))
              queued    <- bus.status(id)
              _          = queued.map(_.state) shouldBe Some(JobState.Queued)
              consumer  <- bus.processAtLeastOnce((_, job) => executed.update(_ :+ job.value).as(Right(()))).compile.drain.start
              completed <- bus.awaitCompletion(id, 20.seconds)
              _          = completed.state shouldBe JobState.Succeeded
              _          = completed.attempts shouldBe 1
              values    <- executed.get
              _          = values shouldBe List(1)
              statuses  <- bus.subscribe(id).compile.toList
              _          = statuses.map(_.state) shouldBe List(JobState.Succeeded)
              _         <- consumer.cancel
            yield ()

          test.timeout(40.seconds)
        }.unsafeRunSync()
      }

      "roll the job back together with the caller's transaction" in withContainers { container =>
        pool(container).use { pool =>
          val name = s"jobs-${UUID.randomUUID()}"
          val bus  = busFor(pool, name)
          val id   = JobId(UUID.randomUUID().toString)

          val test =
            for
              _      <- pool
                          .use(session =>
                            session.transaction.use(_ => bus.submit(session, id, "key", TestJob(1)) >> IO.raiseError(new RuntimeException("rollback")))
                          )
                          .attempt
              status <- bus.status(id)
              _       = status shouldBe None
            yield ()

          test.timeout(20.seconds)
        }.unsafeRunSync()
      }
    }

    "processing jobs" should {

      "fail a job terminally after the attempt limit and keep the partition moving" in withContainers { container =>
        pool(container).use { pool =>
          val name = s"jobs-${UUID.randomUUID()}"
          val bus  = busFor(pool, name, maxAttempts = 2)

          val test =
            for
              attempts  <- Ref.of[IO, Int](0)
              id        <- bus.submit("key", TestJob(1))
              failing   <- bus
                             .processAtLeastOnce((_, _) => attempts.update(_ + 1) >> IO.raiseError(new RuntimeException("boom")))
                             .compile
                             .drain
                             .start
              completed <- bus.awaitCompletion(id, 20.seconds)
              _          = completed.state shouldBe JobState.Failed
              _          = completed.attempts shouldBe 2
              _          = completed.lastError shouldBe Some("boom")
              tries     <- attempts.get
              _          = tries shouldBe 2
              _         <- failing.cancel

              next      <- bus.submit("key", TestJob(2))
              processed <- Ref.of[IO, Boolean](false)
              _         <- bus
                             .processAtLeastOnce((_, _) => processed.set(true).as(Right(())))
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
          val name = s"jobs-${UUID.randomUUID()}"
          val bus  = busFor(pool, name, maxAttempts = 5)

          val test =
            for
              attempts  <- Ref.of[IO, Int](0)
              id        <- bus.submit("key", TestJob(1))
              consumer  <- bus
                             .processAtLeastOnce((_, _) => attempts.update(_ + 1).as(Left("nope")))
                             .compile
                             .drain
                             .start
              completed <- bus.awaitCompletion(id, 20.seconds)
              _          = completed.state shouldBe JobState.Failed
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
          val name = s"jobs-${UUID.randomUUID()}"
          val bus  = busFor(pool, name)

          val test =
            for
              worked    <- Ref.of[IO, Boolean](false)
              committed <- Ref.of[IO, Boolean](false)
              id        <- bus.submit("key", TestJob(7))
              consumer  <- bus
                             .processAtLeastOnce((job: TestJob) =>
                               val result = job.value * 2
                               worked.set(true).as(
                                 Right(committed.set(true).as(Either.cond(result == job.value * 2, (), "unexpected result")))
                               )
                             )
                             .compile
                             .drain
                             .start
              completed <- bus.awaitCompletion(id, 20.seconds)
              _          = completed.state shouldBe JobState.Succeeded
              didWork   <- worked.get
              didCommit <- committed.get
              _          = didWork shouldBe true
              _          = didCommit shouldBe true
              _         <- consumer.cancel
            yield ()

          test.timeout(40.seconds)
        }.unsafeRunSync()
      }

      "dedupe terminal jobs and fence stale attempt mutations" in withContainers { container =>
        pool(container).use { pool =>
          val name  = s"jobs-${UUID.randomUUID()}"
          val store = JobStore(pool)
          val id    = JobId(UUID.randomUUID().toString)

          val test =
            for
              _            <- pool.use(session => session.transaction.use(_ => store.insert(session, name, id, "key")))
              first        <- store.claim(name, id)
              _             = first shouldBe Claim.Acquired(1)
              requeued     <- store.requeue(name, id, "boom", 1)
              _             = requeued shouldBe true
              second       <- store.claim(name, id)
              _             = second shouldBe Claim.Acquired(2)
              staleRequeue <- store.requeue(name, id, "stale", 1)
              staleFail    <- store.fail(name, id, "stale", 1)
              staleSucceed <- store.succeed(name, id, 1)
              _             = List(staleRequeue, staleFail, staleSucceed) shouldBe List(false, false, false)
              succeeded    <- pool.use(session => session.transaction.use(_ => store.succeed(session, name, id, 2)))
              _             = succeeded shouldBe true
              afterDone    <- store.fail(name, id, "too late", 2)
              _             = afterDone shouldBe false
              again        <- store.claim(name, id)
              _             = again shouldBe Claim.Done
              _            <- store.purge(name, java.time.Duration.ZERO)
              gone         <- store.get(name, id)
              _             = gone shouldBe None
            yield ()

          test.timeout(20.seconds)
        }.unsafeRunSync()
      }

      "roll back handler writes when a transactional handler returns Left" in withContainers { container =>
        pool(container).use { pool =>
          val name      = s"jobs-${UUID.randomUUID()}"
          val tableName = s"job_rollback_test_${UUID.randomUUID().toString.replace("-", "_")}"
          val bus       = busFor(pool, name)
          val setup     = pool.use(_.execute(sql"create table #$tableName (v int not null)".command).void)
          val teardown  = pool.use(_.execute(sql"drop table if exists #$tableName".command).void)

          val test =
            (setup >> (for
              id        <- bus.submit("key", TestJob(1))
              consumer  <- bus
                             .processAtLeastOnce { (session, job) =>
                               session.prepare(sql"insert into #$tableName (v) values ($int4)".command).flatMap(_.execute(job.value)) >>
                                 IO.pure(Left("rejected"))
                             }
                             .compile
                             .drain
                             .start
              completed <- bus.awaitCompletion(id, 20.seconds)
              _         <- consumer.cancel
              count     <- pool.use(_.unique(sql"select count(*) from #$tableName".query(int8)))
            yield
              completed.state shouldBe JobState.Failed
              completed.lastError shouldBe Some("rejected")
              count shouldBe 0L)).guarantee(teardown)

          test.timeout(40.seconds)
        }.unsafeRunSync()
      }

      "roll back handler writes when the partition lease is lost before commit" in withContainers { container =>
        pool(container).use { pool =>
          val name      = s"jobs-${UUID.randomUUID()}"
          val tableName = s"job_fence_test_${UUID.randomUUID().toString.replace("-", "_")}"
          val bus       = busFor(pool, name)
          val setup     = pool.use(_.execute(sql"create table #$tableName (v int not null)".command).void)
          val teardown  = pool.use(_.execute(sql"drop table if exists #$tableName".command).void)
          val steal     = sql"""
            update message_consumer
            set lease_generation = lease_generation + 1, locked_until = clock_timestamp() + interval '1 minute'
            where topic = $varchar and consumer_id = $varchar
          """.command

          val test =
            (setup >> (for
              id       <- bus.submit("key", TestJob(42))
              written  <- Deferred[IO, Unit]
              release  <- Deferred[IO, Unit]
              consumer <- bus
                            .processAtLeastOnce { (session, job) =>
                              session.prepare(sql"insert into #$tableName (v) values ($int4)".command).flatMap(_.execute(job.value)) >>
                                written.complete(()) >> release.get.as(Right(()))
                            }
                            .compile
                            .drain
                            .start
              _        <- written.get.timeout(15.seconds)
              _        <- pool.use(_.prepare(steal).flatMap(_.execute((name, name))))
              _        <- release.complete(())
              _        <- IO.sleep(300.millis)
              _        <- consumer.cancel
              count    <- pool.use(_.unique(sql"select count(*) from #$tableName".query(int8)))
              status   <- bus.status(id)
            yield
              count shouldBe 0L
              status.map(_.state) shouldBe Some(JobState.Running))).guarantee(teardown)

          test.timeout(40.seconds)
        }.unsafeRunSync()
      }

      "retry startup consumer registration after a transient database error" in withContainers { container =>
        pool(container).use { pool =>
          val name      = s"jobs-${UUID.randomUUID()}"
          val seqName   = s"job_reg_fail_${UUID.randomUUID().toString.replace("-", "_")}"
          val fnName    = s"${seqName}_fn"
          val trigName  = s"${seqName}_trig"
          val bus       = busFor(pool, name)
          val install   = pool.use { session =>
            session.execute(sql"create sequence #$seqName".command) >>
              session.execute(sql"""
                create function #$fnName() returns trigger language plpgsql as '
                begin
                  if nextval(''#$seqName'') = 1 then
                    raise exception ''transient registration failure'';
                  end if;
                  return new;
                end;
                '
              """.command) >>
              session.execute(sql"""
                create trigger #$trigName before insert on message_consumer
                for each row when (new.consumer_id = '#$name') execute function #$fnName()
              """.command).void
          }
          val uninstall = pool.use { session =>
            session.execute(sql"drop trigger if exists #$trigName on message_consumer".command) >>
              session.execute(sql"drop function if exists #$fnName()".command) >>
              session.execute(sql"drop sequence if exists #$seqName".command).void
          }

          val test =
            (install >> (for
              id        <- bus.submit("key", TestJob(1))
              consumer  <- bus.processAtLeastOnce((_, _) => IO.pure(Right(()))).compile.drain.start
              completed <- bus.awaitCompletion(id, 20.seconds)
              _         <- consumer.cancel
            yield completed.state shouldBe JobState.Succeeded)).guarantee(uninstall)

          test.timeout(40.seconds)
        }.unsafeRunSync()
      }

      "skip undecodable job messages and fail identifiable jobs without blocking the partition" in withContainers { container =>
        pool(container).use { pool =>
          val name                        = s"jobs-${UUID.randomUUID()}"
          given key: JobTopicKey[TestJob] = JobTopicKey(name, partitions = 1, maxAttempts = 3)
          val bus                         = MessageBus.postgres(pool, config).getJobBus[TestJob]
          val log                         = MessageLog(pool)
          val store                       = JobStore(pool)
          val badId                       = JobId(UUID.randomUUID().toString)

          val test =
            for
              _          <- pool.use(s => log.enqueue(s, name, 0, "key", "not-json".getBytes(UTF_8)))
              _          <- pool.use { s =>
                              s.transaction.use { _ =>
                                store.insert(s, name, badId, "key") >>
                                  log.enqueue(s, name, 0, "key", s"""{"id":"${badId.value}","key":"key","payload":{"wrong":true}}""".getBytes(UTF_8))
                              }
                            }
              goodId     <- bus.submit("key", TestJob(99))
              consumer   <- bus.processAtLeastOnce((_, _) => IO.pure(Right(()))).compile.drain.start
              goodStatus <- bus.awaitCompletion(goodId, 20.seconds)
              badStatus  <- bus.status(badId)
              _          <- consumer.cancel
            yield
              goodStatus.state shouldBe JobState.Succeeded
              badStatus.map(_.state) shouldBe Some(JobState.Failed)

          test.timeout(40.seconds)
        }.unsafeRunSync()
      }
    }
  }

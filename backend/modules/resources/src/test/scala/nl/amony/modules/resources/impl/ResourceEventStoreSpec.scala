package nl.amony.modules.resources.impl

import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.dimafeng.testcontainers.GenericContainer
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import fs2.Stream
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.testcontainers.containers.wait.strategy.Wait
import org.typelevel.otel4s.metrics.{Meter, MeterProvider}
import org.typelevel.otel4s.trace.{Tracer, TracerProvider}
import skunk.Session

import nl.amony.modules.auth.api.UserId
import nl.amony.modules.resources.api.*
import nl.amony.modules.resources.dal.ResourceDatabase
import nl.amony.platform.database.{Database, DatabaseConfig}
import nl.amony.platform.messagebus.MessageTopic

class ResourceEventStoreSpec extends AnyWordSpecLike with TestContainerForAll with Matchers:

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

  private def dbConfig(container: GenericContainer): DatabaseConfig =
    DatabaseConfig(
      host     = container.containerIpAddress,
      port     = container.mappedPort(5432),
      database = "test",
      username = "test",
      password = "test",
      poolSize = 1
    )

  private def recordingTopic(published: AtomicReference[List[ResourceEvent]]): MessageTopic[ResourceEvent] =
    new MessageTopic[ResourceEvent]:
      override def publish(event: ResourceEvent): IO[Unit]                       = IO {
        published.updateAndGet(_ :+ event)
        ()
      }
      override def publish(session: Session[IO], event: ResourceEvent): IO[Unit] = IO {
        published.updateAndGet(_ :+ event)
        ()
      }
      override def processAtLeastOnce(consumerId: String, parallelism: Option[Int] = None)(
        processor: ResourceEvent => IO[Unit]
      ): Stream[IO, Unit]                                                        = Stream.empty

  private def newResource(bucketId: BucketId, path: String = "video.mp4"): ResourceInfo =
    ResourceInfo(
      bucketId    = bucketId,
      resourceId  = ResourceId(UUID.randomUUID().toString),
      userId      = UserId("admin"),
      path        = ResourcePath(path),
      partialHash = "test-hash",
      size        = 42L,
      timeAdded   = Some(1000L)
    )

  "DatabaseResourceEventStore" when {

    "processing a moved and modified event" should {

      "update the database row and publish the event" in withContainers { container =>
        Database.makePool(dbConfig(container)).use { pool =>
          val dir       = Files.createTempDirectory("amony-event-store-spec")
          val bucketId  = BucketId(s"bucket-${UUID.randomUUID()}")
          val published = new AtomicReference(List.empty[ResourceEvent])
          val db        = ResourceDatabase(pool)
          val events    = DatabaseResourceEventStore(bucketId, db, recordingTopic(published))
          val res       = newResource(bucketId, "old.mp4")
          val updated   = res.copy(path = ResourcePath("new.mp4"))
          val event     = ResourceMovedAndModified(res.resourceId, ResourcePath("old.mp4"), ResourcePath("new.mp4"), updated)

          val test =
            for
              _      <- db.insertResource(res)
              _      <- events.processEvent(event)
              oldRow <- db.getResourceByPath(bucketId, ResourcePath("old.mp4"))
              newRow <- db.getResourceByPath(bucketId, ResourcePath("new.mp4"))
            yield
              oldRow shouldBe None
              newRow shouldBe Some(updated)
              published.get() shouldBe List(event)

          test.timeout(5.seconds)
        }.unsafeRunSync()
      }
    }
  }

package nl.amony.modules.resources.dal

import java.util.UUID

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Resource}
import com.dimafeng.testcontainers.GenericContainer
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.testcontainers.containers.wait.strategy.Wait
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import skunk.codec.all.*
import skunk.implicits.*
import skunk.{Session, *}

import nl.amony.lib.messagebus.{EventTopic, EventTopicKey, PersistenceCodec, PersistentEventBus}
import nl.amony.modules.auth.api.UserId
import nl.amony.modules.resources.api.*
import nl.amony.{App, DatabaseConfig}

class ResourceEventPublishingSpec extends AnyWordSpecLike with TestContainerForAll with Matchers:

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

  private def configForContainer(container: GenericContainer): DatabaseConfig =
    DatabaseConfig(
      host     = container.containerIpAddress,
      port     = container.mappedPort(5432),
      database = "test",
      username = "test",
      password = "test",
      poolSize = 4
    )

  private def topicFor(pool: Resource[IO, Session[IO]], name: String): EventTopic[ResourceEvent] =
    given PersistenceCodec[ResourceEvent] = PersistenceCodec.fromCirce
    given EventTopicKey[ResourceEvent]    = EventTopicKey(name)
    PersistentEventBus.postgres(pool).getTopic[ResourceEvent]

  private def eventCount(pool: Resource[IO, Session[IO]], name: String): IO[Long] =
    pool.use(_.prepare(sql"select count(*) from event_queue where topic = $varchar".query(int8)).flatMap(_.unique(name)))

  private def resource(bucketId: BucketId): ResourceInfo =
    ResourceInfo(bucketId = bucketId, resourceId = ResourceId(UUID.randomUUID().toString), userId = UserId("admin"), path = "file.mp4", size = 1L)

  "ResourceDatabase" should {

    "commit the resource write and its event together" in withContainers { container =>
      App.makeDatabasePool(configForContainer(container)).use { pool =>
        val db    = ResourceDatabase(pool)
        val name  = s"topic-${UUID.randomUUID()}"
        val topic = topicFor(pool, name)
        val res   = resource(BucketId("bucket"))

        for
          _     <- db.transact(s => db.upsertResourceWith(s, res) >> topic.publish(s, ResourceUpdated(res)))
          found <- db.getResourceById(res.bucketId, res.resourceId)
          count <- eventCount(pool, name)
        yield
          found.map(_.resourceId) shouldBe Some(res.resourceId)
          count shouldBe 1
      }.unsafeRunSync()
    }

    "roll back the resource write and its event together" in withContainers { container =>
      App.makeDatabasePool(configForContainer(container)).use { pool =>
        val db    = ResourceDatabase(pool)
        val name  = s"topic-${UUID.randomUUID()}"
        val topic = topicFor(pool, name)
        val res   = resource(BucketId("bucket"))

        for
          _     <-
            db
              .transact(s => db.upsertResourceWith(s, res) >> topic.publish(s, ResourceUpdated(res)) >> IO.raiseError(new RuntimeException("boom")))
              .attempt
          found <- db.getResourceById(res.bucketId, res.resourceId)
          count <- eventCount(pool, name)
        yield
          found shouldBe None
          count shouldBe 0
      }.unsafeRunSync()
    }
  }

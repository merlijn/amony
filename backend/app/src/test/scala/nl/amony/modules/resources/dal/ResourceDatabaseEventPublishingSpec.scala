package nl.amony.modules.resources.dal

import java.util.UUID
import scala.concurrent.duration.*

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Resource}
import com.dimafeng.testcontainers.GenericContainer
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.testcontainers.containers.wait.strategy.Wait
import org.typelevel.otel4s.metrics.{Meter, MeterProvider}
import org.typelevel.otel4s.trace.{Tracer, TracerProvider}
import skunk.codec.all.*
import skunk.implicits.*
import skunk.{Session, *}

import nl.amony.modules.auth.api.UserId
import nl.amony.modules.resources.api.*
import nl.amony.platform.database.{Database, DatabaseConfig}
import nl.amony.platform.messagebus.{MessageBus, MessageTopic, MessageTopicKey}

class ResourceDatabaseEventPublishingSpec extends AnyWordSpecLike with TestContainerForAll with Matchers:

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

  private def configForContainer(container: GenericContainer, poolSize: Int = 4): DatabaseConfig =
    DatabaseConfig(
      host     = container.containerIpAddress,
      port     = container.mappedPort(5432),
      database = "test",
      username = "test",
      password = "test",
      poolSize = poolSize
    )

  private def topicFor(pool: Resource[IO, Session[IO]], name: String): MessageTopic[ResourceEvent] =
    given MessageTopicKey[ResourceEvent] = MessageTopicKey(name, _.messageKey)
    MessageBus.postgres(pool).getTopic[ResourceEvent]

  private def eventCount(pool: Resource[IO, Session[IO]], name: String): IO[Long] =
    pool.use(_.prepare(sql"select count(*) from message_log where topic = $varchar".query(int8)).flatMap(_.unique(name)))

  private def resource(bucketId: BucketId): ResourceInfo =
    ResourceInfo(bucketId = bucketId, resourceId = ResourceId(UUID.randomUUID().toString), userId = UserId("admin"), path = "file.mp4", size = 1L)

  "ResourceDatabase" should {

    "commit the resource write and its event together" in withContainers { container =>
      Database.makePool(configForContainer(container)).use { pool =>
        val db    = ResourceDatabase(pool)
        val name  = s"topic-${UUID.randomUUID()}"
        val topic = topicFor(pool, name)
        val res   = resource(BucketId("bucket"))

        for
          _     <- db.transact(s => db.upsertResource(res) >> topic.publish(s, ResourceModified(res)))
          found <- db.getResourceById(res.bucketId, res.resourceId)
          count <- eventCount(pool, name)
        yield
          found.map(_.resourceId) shouldBe Some(res.resourceId)
          count shouldBe 1
      }.unsafeRunSync()
    }

    "roll back the resource write and its event together" in withContainers { container =>
      Database.makePool(configForContainer(container)).use { pool =>
        val db    = ResourceDatabase(pool)
        val name  = s"topic-${UUID.randomUUID()}"
        val topic = topicFor(pool, name)
        val res   = resource(BucketId("bucket"))

        for
          _     <-
            db
              .transact(s => db.upsertResource(res) >> topic.publish(s, ResourceModified(res)) >> IO.raiseError(new RuntimeException("boom")))
              .attempt
          found <- db.getResourceById(res.bucketId, res.resourceId)
          count <- eventCount(pool, name)
        yield
          found shouldBe None
          count shouldBe 0
      }.unsafeRunSync()
    }

    "reuse the active session across all database operations inside transact without acquiring extra pool connections" in withContainers {
      container =>
        Database.makePool(configForContainer(container, poolSize = 1)).use { pool =>
          val db     = ResourceDatabase(pool)
          val bucket = BucketId(s"bucket-${UUID.randomUUID()}")
          val res    = resource(bucket).copy(partialHash = Some("hash-1"), tags = Set(Tag.user("initial")))

          val tx = db.transact { _ =>
            for
              _          <- db.insertResource(res)
              byId       <- db.getResourceById(bucket, res.resourceId)
              forUpdate  <- db.getResourceByIdForUpdate(bucket, res.resourceId)
              byHash     <- db.getResourceByPartialHash(bucket, "hash-1")
              byPath     <- db.getResourceByPath(bucket, res.path)
              afterMeta  <- db.updateUserMeta(bucket, res.resourceId, Some("title"), Some("desc"), TagOwner.User, List("a", "b"))
              afterTags  <- db.updateResourceTags(bucket, res.resourceId, Set(Tag.user("c")), Set(Tag.user("a")))
              afterThumb <- db.updateThumbnailTimestamp(bucket, res.resourceId, 1234)
              afterOwner <- db.setTagsForOwner(bucket, res.resourceId, TagOwner.System, List("sys"))
              _          <- db.move(bucket, res.resourceId, "moved.mp4")
              moved      <- db.getResourceByPath(bucket, "moved.mp4")
              size       <- db.bucketSize(bucket)
              _          <- db.deleteResource(bucket, res.resourceId)
              afterDel   <- db.getResourceById(bucket, res.resourceId)
            yield
              byId.map(_.resourceId) shouldBe Some(res.resourceId)
              forUpdate.map(_.resourceId) shouldBe Some(res.resourceId)
              byHash.map(_.resourceId) shouldBe List(res.resourceId)
              byPath.map(_.resourceId) shouldBe Some(res.resourceId)
              afterMeta.flatMap(_.title) shouldBe Some("title")
              afterTags.map(_.userLabels) shouldBe Some(Set("b", "c"))
              afterThumb.flatMap(_.thumbnailTimestamp) shouldBe Some(1234)
              afterOwner.flatMap(_.labelsByOwner.get(TagOwner.System)) shouldBe Some(Set("sys"))
              moved.map(_.path) shouldBe Some("moved.mp4")
              size shouldBe 1L
              afterDel shouldBe None
          }

          tx.timeout(5.seconds)
        }.unsafeRunSync()
    }
  }

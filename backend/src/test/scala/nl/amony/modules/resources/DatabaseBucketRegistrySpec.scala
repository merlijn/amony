package nl.amony.modules.resources

import java.nio.file.{Files, Path}
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref}
import com.dimafeng.testcontainers.GenericContainer
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import fs2.Stream
import org.mockito.IdiomaticMockito.returns
import org.mockito.Mockito.RETURNS_DEFAULTS
import org.mockito.scalatest.MockitoSugar
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.testcontainers.containers.wait.strategy.Wait
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import skunk.Session

import nl.amony.lib.messagebus.EventTopic
import nl.amony.modules.auth.api.{Role, UserId}
import nl.amony.modules.resources.api.*
import nl.amony.modules.resources.dal.{BucketsDal, ResourceDatabase}
import nl.amony.{App, DatabaseConfig}

class DatabaseBucketRegistrySpec extends AnyWordSpecLike with TestContainerForAll with Matchers with MockitoSugar {

  override val containerDef: GenericContainer.Def[GenericContainer] =
    GenericContainer.Def(
      "postgres:17.2",
      exposedPorts = Seq(5432),
      waitStrategy = Wait.forLogMessage(".*database system is ready to accept connections.*", 2),
      env          = Map("POSTGRES_USER" -> "test", "PGUSER" -> "test", "POSTGRES_PASSWORD" -> "test", "POSTGRES_DB" -> "test")
    )

  given Tracer[IO] = Tracer.noop[IO]
  given Meter[IO]  = Meter.noop[IO]

  private def bucketConfig(id: String, path: Path): LocalDirectoryConfig =
    LocalDirectoryConfig(
      id                 = id,
      requiredRole       = Some(Role.Authenticated),
      path               = path,
      sync               = ScanConfig(
        enabled         = false,
        syncOnStartup   = false,
        newFilesOwner   = "admin",
        pollInterval    = 60.seconds,
        includePatterns = List("**/*.{mp4,jpg}"),
        excludePatterns = List("**/.*")
      ),
      hashingAlgorithm   = PartialHash,
      relativeUploadPath = Path.of("_upload")
    )

  private def tempDir(): Path = Files.createTempDirectory("amony-bucket-test")

  private def withExcludes(config: LocalDirectoryConfig, excludes: List[String]): LocalDirectoryConfig =
    config.copy(sync = config.sync.copy(excludePatterns = excludes))

  private def mockBucket(config: ResourceBucketConfig): ResourceBucket =
    val bucket = mock[ResourceBucket](RETURNS_DEFAULTS)
    bucket.id returns BucketId(config.id)
    bucket.requiredRole returns None
    bucket

  private def resource(bucketId: BucketId): ResourceInfo =
    ResourceInfo(bucketId = bucketId, resourceId = ResourceId(UUID.randomUUID().toString), userId = UserId("admin"), path = "file.mp4", size = 1L)

  private def recordingTopic(published: AtomicReference[List[ResourceEvent]]): EventTopic[ResourceEvent] =
    new EventTopic[ResourceEvent]:
      override def publish(event: ResourceEvent): IO[Unit]                                                         = IO {
        published.updateAndGet(_ :+ event)
        ()
      }
      override def publish(session: Session[IO], event: ResourceEvent): IO[Unit]                                   = IO {
        published.updateAndGet(_ :+ event)
        ()
      }
      override def processAtLeastOnce(processorId: String)(processor: ResourceEvent => IO[Unit]): Stream[IO, Unit] = Stream.empty

  "DatabaseBucketRegistry" should {
    "not insert the default bucket when its directory does not exist" in {
      withContainers { container =>
        val dbConfig    = DatabaseConfig(container.containerIpAddress, container.mappedPort(5432), "test", "test", 3, "test")
        val missingPath = tempDir().resolve("missing")

        val test =
          App.makeDatabasePool(dbConfig).use { pool =>
            val bucketsDal = BucketsDal(pool)
            val factory    = (config: ResourceBucketConfig) => IO.pure((mockBucket(config), IO.unit))

            for
              configs <- DatabaseBucketRegistry
                           .resource(bucketConfig("media", missingPath), bucketsDal, recordingTopic(new AtomicReference(List.empty)), factory)
                           .use(_.allConfigs)
              _        = configs shouldBe empty
              stored  <- bucketsDal.anyExist()
              _        = stored shouldBe false
            yield ()
          }

        test.unsafeRunSync()
      }
    }

    "seed, create, update and delete buckets" in {
      withContainers { container =>
        val dbConfig = DatabaseConfig(container.containerIpAddress, container.mappedPort(5432), "test", "test", 3, "test")

        val defaultPath = tempDir()
        val otherPath   = tempDir()
        Files.createDirectories(defaultPath.resolve("sub"))

        val test =
          App.makeDatabasePool(dbConfig).use { pool =>
            val bucketsDal = BucketsDal(pool)
            val resourceDb = ResourceDatabase(pool)
            val published  = new AtomicReference(List.empty[ResourceEvent])

            for
              started  <- Ref.of[IO, List[String]](Nil)
              active   <- Ref.of[IO, Map[String, Int]](Map.empty)
              maxSyncs <- Ref.of[IO, Int](0)
              sync      = (id: String) =>
                            (started.update(id :: _) >>
                              active.updateAndGet(m => m.updated(id, m.getOrElse(id, 0) + 1)).flatMap(m => maxSyncs.update(_ max m(id))) >>
                              IO.never[Unit]).onCancel(active.update(m => m.updated(id, m(id) - 1)))
              factory   = (config: ResourceBucketConfig) => IO.pure((mockBucket(config), sync(config.id)))
              registry  = DatabaseBucketRegistry.resource(bucketConfig("media", defaultPath), bucketsDal, recordingTopic(published), factory)
              _        <- registry.use { r =>
                            for
                              seeded      <- r.allConfigs
                              _            = seeded.map(_.config) shouldBe List(bucketConfig("media", defaultPath))
                              overlapping <- r.create(bucketConfig("other", defaultPath.resolve("sub")))
                              _            = overlapping shouldBe a[Left[BucketError.Invalid, ?]]
                              invalidId   <- r.create(bucketConfig("Not Valid", otherPath))
                              _            = invalidId shouldBe a[Left[BucketError.Invalid, ?]]
                              invalidGlob <- r.create(withExcludes(bucketConfig("other", otherPath), List("{unclosed")))
                              _            = invalidGlob shouldBe Left(BucketError.Invalid("Invalid pattern: '{unclosed'"))
                              created     <- r.create(bucketConfig("other", otherPath))
                              _            = created shouldBe Right(())
                              duplicate   <- r.create(bucketConfig("other", otherPath))
                              _            = duplicate shouldBe Left(BucketError.AlreadyExists(BucketId("other")))
                              original    <- r.getConfig(BucketId("other")).map(_.get)
                              relativePath = Path.of("").toAbsolutePath.relativize(otherPath)
                              changed      = bucketConfig("other", relativePath).copy(generatePreviewsOnAdd = true, requiredRole = None)
                              updated     <- r.update(changed, original.updatedAt)
                              _            = updated shouldBe Right(())
                              stored      <- bucketsDal.getById(BucketId("other"))
                              _            = stored.map(_.config) shouldBe Some(changed.copy(path = otherPath))
                              _            = stored.get.updatedAt.isAfter(original.updatedAt) shouldBe true
                              stale       <- r.update(changed.copy(generatePreviewsOnAdd = false), original.updatedAt)
                              _            = stale shouldBe Left(BucketError.Modified(BucketId("other")))
                              missingUpd  <- r.update(bucketConfig("unknown", otherPath), original.updatedAt)
                              _            = missingUpd shouldBe Left(BucketError.NotFound(BucketId("unknown")))
                              _           <- resourceDb.insertResource(resource(BucketId("other")).copy(tags = Set("a")))
                              notEmpty    <- r.delete(BucketId("other"), force = false)
                              _            = notEmpty shouldBe Left(BucketError.NotEmpty(BucketId("other"), 1))
                              forced      <- r.delete(BucketId("other"), force = true)
                              _            = forced shouldBe Right(())
                              remaining   <- resourceDb.getAll(BucketId("other"))
                              _            = remaining shouldBe empty
                              _            = published.get() should contain(BucketDeleted(BucketId("other")))
                              missing     <- r.delete(BucketId("other"), force = true)
                              _            = missing shouldBe Left(BucketError.NotFound(BucketId("other")))
                              _           <- IO.sleep(100.millis)
                              startedIds  <- started.get
                              _            = startedIds.sorted shouldBe List("media", "other", "other")
                              activeSyncs <- active.get
                              _            = activeSyncs shouldBe Map("media" -> 1, "other" -> 0)
                              maxActive   <- maxSyncs.get
                              _            = maxActive shouldBe 1
                            yield ()
                          }
              // the default bucket is not inserted again on a restart
              reseeded <- bucketsDal.insertIfNoneExist(bucketConfig("ignored", otherPath))
              _         = reseeded shouldBe false
              all      <- bucketsDal.getAll()
              _         = all.map(_.config.id) shouldBe List("media")
            yield ()
          }

        test.unsafeRunSync()
      }
    }
  }
}

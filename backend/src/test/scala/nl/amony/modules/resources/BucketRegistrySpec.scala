package nl.amony.modules.resources

import java.nio.file.{Files, Path}
import java.util.UUID
import scala.concurrent.duration.*

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref}
import com.dimafeng.testcontainers.GenericContainer
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.testcontainers.containers.wait.strategy.Wait
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer

import nl.amony.modules.auth.api.{Role, UserId}
import nl.amony.modules.resources.ResourceConfig.{LocalDirectoryConfig, PartialHash, ScanConfig}
import nl.amony.modules.resources.api.*
import nl.amony.modules.resources.dal.{BucketsDal, ResourceDatabase}
import nl.amony.modules.search.api.{Query, SearchResult, SearchService}
import nl.amony.{App, DatabaseConfig}

class BucketRegistrySpec extends AnyWordSpecLike with TestContainerForAll with Matchers {

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
        enabled            = false,
        syncOnStartup      = false,
        newFilesOwner      = "admin",
        scanParallelFactor = 2,
        pollInterval       = 60.seconds,
        includePatterns    = List("**/*.{mp4,jpg}"),
        excludePatterns    = List("**/.*")
      ),
      hashingAlgorithm   = PartialHash,
      relativeUploadPath = Path.of("_upload")
    )

  private def tempDir(): Path = Files.createTempDirectory("amony-bucket-test")

  private def withExcludes(config: LocalDirectoryConfig, excludes: List[String]): LocalDirectoryConfig =
    config.copy(sync = config.sync.copy(excludePatterns = excludes))

  private class FakeBucket(val id: BucketId, val requiredRole: Option[Role]) extends ResourceBucket:
    def getResource(resourceId: ResourceId)                                                                            = ???
    def updateUserMeta(resourceId: ResourceId, title: Option[String], description: Option[String], tags: List[String]) = ???
    def updateResourceTags(resourceIds: Set[ResourceId], tagsToAdd: Set[String], tagsToRemove: Set[String])            = ???
    def updateThumbnailTimestamp(resourceId: ResourceId, timestamp: Int)                                               = ???
    def deleteResource(resourceId: ResourceId)                                                                         = ???
    def getOrCreate(resourceId: ResourceId, operation: ResourceOperation)                                              = ???
    def uploadResource(userId: UserId, fileName: String, source: fs2.Stream[IO, Byte])                                 = ???
    def getAllResources                                                                                                = ???

  private class FakeSearchService(deleted: Ref[IO, List[BucketId]]) extends SearchService:
    def deleteBucket(bucketId: BucketId): IO[Unit]                  = deleted.update(bucketId :: _)
    def searchMedia(query: Query): IO[SearchResult]                 = ???
    def searchAll(query: Query): fs2.Stream[IO, ResourceInfo]       = ???
    def indexAll(resources: fs2.Stream[IO, ResourceInfo]): IO[Unit] = ???
    def index(resource: ResourceInfo): IO[Unit]                     = ???
    def forceCommit(): IO[Unit]                                     = ???

  private def resource(bucketId: BucketId): ResourceInfo =
    ResourceInfo(bucketId = bucketId, resourceId = ResourceId(UUID.randomUUID().toString), userId = UserId("admin"), path = "file.mp4", size = 1L)

  "The bucket registry" should {
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

            for
              started  <- Ref.of[IO, List[String]](Nil)
              deleted  <- Ref.of[IO, List[BucketId]](Nil)
              factory   = (config: ResourceConfig.ResourceBucketConfig) =>
                            IO.pure((FakeBucket(BucketId(config.id), None), started.update(config.id :: _) >> IO.never))
              registry  = BucketRegistry.resource(bucketConfig("media", defaultPath), bucketsDal, resourceDb, FakeSearchService(deleted), factory)
              _        <- registry.use { r =>
                            for
                              seeded      <- r.allConfigs
                              _            = seeded shouldBe List(bucketConfig("media", defaultPath))
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
                              updated     <- r.update(bucketConfig("other", otherPath).copy(generatePreviewsOnAdd = true, requiredRole = None))
                              _            = updated shouldBe Right(())
                              stored      <- bucketsDal.getById(BucketId("other"))
                              _            = stored shouldBe Some(bucketConfig("other", otherPath).copy(generatePreviewsOnAdd = true, requiredRole = None))
                              _           <- resourceDb.insertResource(resource(BucketId("other")).copy(tags = Set("a")))
                              notEmpty    <- r.delete(BucketId("other"), force = false)
                              _            = notEmpty shouldBe Left(BucketError.NotEmpty(BucketId("other"), 1))
                              forced      <- r.delete(BucketId("other"), force = true)
                              _            = forced shouldBe Right(())
                              remaining   <- resourceDb.getAll(BucketId("other"))
                              _            = remaining shouldBe empty
                              deletedIds  <- deleted.get
                              _            = deletedIds shouldBe List(BucketId("other"))
                              missing     <- r.delete(BucketId("other"), force = true)
                              _            = missing shouldBe Left(BucketError.NotFound(BucketId("other")))
                              _           <- IO.sleep(100.millis)
                              startedIds  <- started.get
                              _            = startedIds.sorted shouldBe List("media", "other", "other")
                            yield ()
                          }
              // the default bucket is not inserted again on a restart
              reseeded <- bucketsDal.insertIfNoneExist(bucketConfig("ignored", otherPath))
              _         = reseeded shouldBe false
              all      <- bucketsDal.getAll()
              _         = all.map(_.id) shouldBe List("media")
            yield ()
          }

        test.unsafeRunSync()
      }
    }
  }
}

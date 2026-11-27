package nl.amony.modules.resources.impl

import java.nio.file.{Files, Path}
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*

import cats.data.EitherT
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.dimafeng.testcontainers.GenericContainer
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import fs2.Stream
import org.mockito.IdiomaticMockito.returns
import org.mockito.Mockito.{RETURNS_DEFAULTS, times, verify, verifyNoMoreInteractions}
import org.mockito.scalatest.MockitoSugar
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.testcontainers.containers.wait.strategy.Wait
import org.typelevel.otel4s.metrics.{Meter, MeterProvider}
import org.typelevel.otel4s.trace.{Tracer, TracerProvider}
import skunk.Session

import nl.amony.modules.auth.api.{Role, UserId}
import nl.amony.modules.resources.api.*
import nl.amony.modules.resources.dal.ResourceDatabase
import nl.amony.platform.database.{Database, DatabaseConfig}
import nl.amony.platform.messagebus.MessageTopic

class ResourceBucketImplSpec extends AnyWordSpecLike with TestContainerForAll with Matchers with MockitoSugar:

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

  private def localConfig(id: String, path: Path, generatePreviewsOnAdd: Boolean = false): LocalDirectoryConfig =
    LocalDirectoryConfig(
      id                    = id,
      requiredRole          = Some(Role.Authenticated),
      path                  = path,
      generatePreviewsOnAdd = generatePreviewsOnAdd,
      sync                  = ScanConfig(
        enabled         = false,
        syncOnStartup   = false,
        newFilesOwner   = "admin",
        pollInterval    = 60.seconds,
        includePatterns = List("**/*.{mp4,jpg}"),
        excludePatterns = List("**/.*")
      ),
      hashingAlgorithm      = PartialHash,
      relativeUploadPath    = Path.of("_upload")
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

  private def makeBucket(
    config: LocalDirectoryConfig,
    db: ResourceDatabase,
    published: AtomicReference[List[ResourceEvent]],
    ops: BucketOperations = null
  ): ResourceBucketImpl =
    val topic   = recordingTopic(published)
    val store   = LocalDirectoryStore(config)
    val staging = LocalStagingArea(config.tempPath)
    val events  = DatabaseResourceEventStore(BucketId(config.id), db, topic)
    ResourceBucketImpl(config, 2, db, topic, ops, store, staging, events, null)

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

  "ResourceBucketImpl and ResourceProcessor" should {

    "publish ResourceUserMetaUpdated with computed tag diffs on updateUserMeta using a single-connection pool" in withContainers { container =>
      Database.makePool(dbConfig(container)).use { pool =>
        val dir       = Files.createTempDirectory("amony-bucket-spec")
        val cfg       = localConfig(s"bucket-${UUID.randomUUID()}", dir)
        val db        = ResourceDatabase(pool)
        val published = new AtomicReference(List.empty[ResourceEvent])
        val bucket    = makeBucket(cfg, db, published)
        val initial   = newResource(bucket.id).copy(
          title       = Some("old title"),
          description = Some("old desc"),
          tags        = Set(Tag.user("keep"), Tag.user("remove-me"), Tag.system("sys-tag"))
        )

        val test =
          for
            _      <- db.insertResource(initial)
            _      <- bucket.updateUserMeta(initial.resourceId, Some("new title"), Some("new desc"), List("keep", "added"))
            stored <- db.getResourceById(bucket.id, initial.resourceId)
          yield
            val expected = initial.copy(
              title       = Some("new title"),
              description = Some("new desc"),
              tags        = Set(Tag.user("keep"), Tag.user("added"), Tag.system("sys-tag"))
            )
            stored shouldBe Some(expected)
            published.get() shouldBe List(
              ResourceUserMetaUpdated(
                resource           = expected,
                updatedDescription = Some("new desc"),
                updatedTitle       = Some("new title"),
                tagsAdded          = Set(Tag.user("added")),
                tagsRemoved        = Set(Tag.user("remove-me"))
              )
            )

        test.timeout(5.seconds)
      }.unsafeRunSync()
    }

    "publish ResourceUserMetaUpdated for bulk tag updates using a single-connection pool" in withContainers { container =>
      Database.makePool(dbConfig(container)).use { pool =>
        val dir       = Files.createTempDirectory("amony-bucket-spec")
        val cfg       = localConfig(s"bucket-${UUID.randomUUID()}", dir)
        val db        = ResourceDatabase(pool)
        val published = new AtomicReference(List.empty[ResourceEvent])
        val bucket    = makeBucket(cfg, db, published)
        val res1      = newResource(bucket.id, "a.mp4").copy(tags = Set(Tag.user("old")))
        val res2      = newResource(bucket.id, "b.mp4").copy(tags = Set(Tag.user("old"), Tag.user("stay")))

        val test =
          for
            _ <- db.insertResource(res1)
            _ <- db.insertResource(res2)
            _ <- bucket.updateResourceTags(Set(res1.resourceId, res2.resourceId), Set(Tag.user("new")), Set(Tag.user("old")))
          yield
            val events = published.get().collect { case e: ResourceUserMetaUpdated => e }
            events.size shouldBe 2
            events.foreach { event =>
              event.updatedTitle shouldBe None
              event.updatedDescription shouldBe None
              event.tagsAdded shouldBe Set(Tag.user("new"))
              event.tagsRemoved shouldBe Set(Tag.user("old"))
            }
            events.map(e => e.resource.resourceId -> e.resource.userLabels).toMap shouldBe Map(
              res1.resourceId -> Set("new"),
              res2.resourceId -> Set("stay", "new")
            )

        test.timeout(5.seconds)
      }.unsafeRunSync()
    }

    "publish ResourceThumbnailUpdated on updateThumbnailTimestamp using a single-connection pool" in withContainers { container =>
      Database.makePool(dbConfig(container)).use { pool =>
        val dir       = Files.createTempDirectory("amony-bucket-spec")
        val cfg       = localConfig(s"bucket-${UUID.randomUUID()}", dir)
        val db        = ResourceDatabase(pool)
        val published = new AtomicReference(List.empty[ResourceEvent])
        val bucket    = makeBucket(cfg, db, published)
        val res       = newResource(bucket.id)

        val test =
          for
            _ <- db.insertResource(res)
            _ <- bucket.updateThumbnailTimestamp(res.resourceId, 5000)
          yield published.get() shouldBe List(
            ResourceThumbnailUpdated(5000, res.copy(thumbnailTimestamp = Some(5000)))
          )

        test.timeout(5.seconds)
      }.unsafeRunSync()
    }

    "delete the database row and publish ResourceDeleted on deleteResource, then delete the file and derived files in ResourceProcessor" in withContainers {
      container =>
        Database.makePool(dbConfig(container)).use { pool =>
          val dir       = Files.createTempDirectory("amony-bucket-spec")
          val file      = Files.writeString(dir.resolve("video.mp4"), "content")
          val cfg       = localConfig(s"bucket-${UUID.randomUUID()}", dir)
          val db        = ResourceDatabase(pool)
          val published = new AtomicReference(List.empty[ResourceEvent])
          val bucket    = makeBucket(cfg, db, published)
          val res       = newResource(bucket.id, "video.mp4")

          Files.createDirectories(cfg.cachePath)
          val derived = Files.writeString(cfg.cachePath.resolve(s"${res.resourceId}_v1_w512.webp"), "thumb")

          val registry  = mock[BucketConsumerRegistry](RETURNS_DEFAULTS)
          registry.getConsumer(bucket.id) returns IO.pure(Some(bucket))
          val processor = ResourceProcessor(registry)

          val test =
            for
              _      <- db.insertResource(res)
              _      <- bucket.deleteResource(res.resourceId)
              stored <- db.getResourceById(bucket.id, res.resourceId)
              _       = stored shouldBe None
              _       = published.get() shouldBe List(ResourceDeleted(res))
              _       = Files.exists(file) shouldBe true
              _       = Files.exists(derived) shouldBe true
              _      <- processor.processEvent(ResourceDeleted(res))
            yield
              Files.exists(file) shouldBe false
              Files.exists(derived) shouldBe false

          test.timeout(5.seconds)
        }.unsafeRunSync()
    }

    "only generate previews for content and thumbnail events and invalidate derived files on modify or delete" in {
      val bucketId = BucketId("media")
      val res      = newResource(bucketId)

      val ops   = mock[BucketOperations](RETURNS_DEFAULTS)
      val store = mock[ResourceStore](RETURNS_DEFAULTS)
      ops.generatePreviews(res) returns IO.unit
      store.delete(StoreKey.Resource(res.path)) returns IO.unit
      store.deleteDerived(res.resourceId) returns IO.unit

      val config = localConfig(s"bucket-${UUID.randomUUID()}", Files.createTempDirectory("amony-bucket-spec"), generatePreviewsOnAdd = true)
      val bucket = ResourceBucketImpl(config, 1, null, null, ops, store, null, null, null)

      val test =
        for
          _ <- bucket.processEvent(ResourceAdded(res))
          _ <- bucket.processEvent(ResourceModified(res))
          _ <- bucket.processEvent(ResourceThumbnailUpdated(1000, res))
          _ <- bucket.processEvent(ResourceMovedAndModified(res.resourceId, ResourcePath("old.mp4"), ResourcePath("new.mp4"), res))
          _ <- bucket.processEvent(ResourceDeleted(res))
          _ <- bucket.processEvent(ResourceUserMetaUpdated(res, Some("desc"), Some("title"), Set.empty, Set.empty))
          _ <- bucket.processEvent(ResourceMoved(bucketId, res.resourceId, ResourcePath("old.mp4"), ResourcePath("new.mp4")))
          _ <- bucket.processEvent(ResourceFileMetaChanged(bucketId, res.resourceId, 123L, None))
          _ <- bucket.processEvent(BucketDeleted(bucketId))
        yield {
          verify(ops, times(4)).generatePreviews(res)
          verifyNoMoreInteractions(ops)
          verify(store, times(3)).deleteDerived(res.resourceId)
          verify(store, times(1)).delete(StoreKey.Resource(res.path))
          verifyNoMoreInteractions(store)
        }

      test.unsafeRunSync()
    }

    "update the database row on ResourceMovedAndModified" in withContainers { container =>
      Database.makePool(dbConfig(container)).use { pool =>
        val dir       = Files.createTempDirectory("amony-bucket-spec")
        val cfg       = localConfig(s"bucket-${UUID.randomUUID()}", dir)
        val db        = ResourceDatabase(pool)
        val published = new AtomicReference(List.empty[ResourceEvent])
        val events    = DatabaseResourceEventStore(BucketId(cfg.id), db, recordingTopic(published))
        val res       = newResource(BucketId(cfg.id), "old.mp4")
        val updated   = res.copy(path = ResourcePath("new.mp4"))
        val event     = ResourceMovedAndModified(res.resourceId, ResourcePath("old.mp4"), ResourcePath("new.mp4"), updated)

        val test =
          for
            _      <- db.insertResource(res)
            _      <- events.processEvent(event)
            oldRow <- db.getResourceByPath(BucketId(cfg.id), ResourcePath("old.mp4"))
            newRow <- db.getResourceByPath(BucketId(cfg.id), ResourcePath("new.mp4"))
          yield
            oldRow shouldBe None
            newRow shouldBe Some(updated)
            published.get() shouldBe List(event)

        test.timeout(5.seconds)
      }.unsafeRunSync()
    }

    "only publish ResourceModified when a rescan finds changes" in withContainers { container =>
      // The rescan streams rows while upserting, so it needs more than one pooled connection.
      Database.makePool(dbConfig(container).copy(poolSize = 4)).use { pool =>
        val dir       = Files.createTempDirectory("amony-bucket-spec")
        val cfg       = localConfig(s"bucket-${UUID.randomUUID()}", dir)
        val db        = ResourceDatabase(pool)
        val published = new AtomicReference(List.empty[ResourceEvent])
        val ops       = mock[BucketOperations](RETURNS_DEFAULTS)
        val meta      = ContentMeta(contentType = Some("text/plain"))
        val unchanged = newResource(BucketId(cfg.id), "same.txt").copy(contentMeta = meta)
        val changed   = newResource(BucketId(cfg.id), "changed.txt").copy(contentMeta = ContentMeta.empty)
        ops.contentMeta(ContentLocation.Resource(ResourcePath("same.txt"))) returns EitherT.pure[IO, ContentUnreadable](meta)
        ops.contentMeta(ContentLocation.Resource(ResourcePath("changed.txt"))) returns EitherT.pure[IO, ContentUnreadable](meta)
        val bucket    = makeBucket(cfg, db, published, ops)

        val test =
          for
            _ <- db.insertResource(unchanged)
            _ <- db.insertResource(changed)
            _ <- bucket.reScanAllMetadata()
          yield published.get() shouldBe List(ResourceModified(changed.copy(contentMeta = meta)))

        test.timeout(5.seconds)
      }.unsafeRunSync()
    }
  }

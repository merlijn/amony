package nl.amony.modules.resources.impl

import java.nio.file.{Files, Path}
import java.util.UUID
import scala.concurrent.duration.*

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import nl.amony.modules.auth.api.UserId
import nl.amony.modules.resources.api.*

class LocalDirectoryStoreSpec extends AnyWordSpecLike with Matchers:

  private def config(dir: Path): LocalDirectoryConfig =
    LocalDirectoryConfig(
      id                    = s"bucket-${UUID.randomUUID()}",
      path                  = dir,
      sync                  = ScanConfig(
        enabled         = false,
        syncOnStartup   = false,
        newFilesOwner   = "admin",
        pollInterval    = 60.seconds,
        includePatterns = List("**/*"),
        excludePatterns = Nil
      ),
      hashingAlgorithm      = PartialHash,
      relativeUploadPath    = Path.of("_upload"),
      generatePreviewsOnAdd = false
    )

  private def resource(path: String): ResourceInfo =
    ResourceInfo(
      bucketId    = BucketId("bucket"),
      resourceId  = ResourceId(UUID.randomUUID().toString),
      userId      = UserId("admin"),
      path        = ResourcePath(path),
      partialHash = "test-hash",
      size        = 1L
    )

  "LocalDirectoryStore" should {

    "return the content of an existing resource" in {
      val dir   = Files.createTempDirectory("amony-store-spec")
      Files.writeString(dir.resolve("video.mp4"), "content")
      val store = LocalDirectoryStore(config(dir))

      store.content(resource("video.mp4"), Some("etag-1")).unsafeRunSync() match
        case Some(ResourceContent.Streamed(streamed)) =>
          streamed.eTag shouldBe Some("etag-1")
          streamed.stream.through(fs2.text.utf8.decode).compile.string.unsafeRunSync() shouldBe "content"
        case other                                    => fail(s"Expected streamed content, got $other")
    }

    "return no content when the object does not exist" in {
      val dir   = Files.createTempDirectory("amony-store-spec")
      val store = LocalDirectoryStore(config(dir))

      store.content(resource("missing.mp4"), None).unsafeRunSync() shouldBe None
    }

    "move a staged file into the bucket on commit" in {
      val dir    = Files.createTempDirectory("amony-store-spec")
      val cfg    = config(dir)
      val store  = LocalDirectoryStore(cfg)
      val staged = cfg.tempPath.resolve("resource-upload")
      Files.createDirectories(cfg.tempPath)
      Files.writeString(staged, "content")

      val stored = store.commit(StagingId("resource-upload"), "video.mp4").value.unsafeRunSync()

      stored.map(_.path) shouldBe Right("video.mp4")
      Files.exists(dir.resolve("video.mp4")) shouldBe true
      Files.exists(staged) shouldBe false
    }

    "delete a staged file on discard" in {
      val dir    = Files.createTempDirectory("amony-store-spec")
      val cfg    = config(dir)
      val store  = LocalDirectoryStore(cfg)
      val staged = cfg.tempPath.resolve("resource-upload")
      Files.createDirectories(cfg.tempPath)
      Files.writeString(staged, "content")

      store.discard(StagingId("resource-upload")).unsafeRunSync()

      Files.exists(staged) shouldBe false
    }

    "ignore a missing staged file on discard" in {
      val store = LocalDirectoryStore(config(Files.createTempDirectory("amony-store-spec")))

      store.discard(StagingId("missing-upload")).unsafeRunSync()
    }

    "delete a stored resource" in {
      val dir   = Files.createTempDirectory("amony-store-spec")
      val file  = Files.writeString(dir.resolve("video.mp4"), "content")
      val store = LocalDirectoryStore(config(dir))

      store.delete(ResourcePath("video.mp4")).unsafeRunSync()

      Files.exists(file) shouldBe false
    }

    "ignore a missing resource on delete" in {
      val store = LocalDirectoryStore(config(Files.createTempDirectory("amony-store-spec")))

      store.delete(ResourcePath("missing.mp4")).unsafeRunSync()
    }

    "resolve a unique name when the target already exists" in {
      val dir    = Files.createTempDirectory("amony-store-spec")
      val cfg    = config(dir)
      val store  = LocalDirectoryStore(cfg)
      Files.writeString(dir.resolve("video.mp4"), "existing")
      val staged = cfg.tempPath.resolve("resource-upload")
      Files.createDirectories(cfg.tempPath)
      Files.writeString(staged, "content")

      store.commit(StagingId("resource-upload"), "video.mp4").value.unsafeRunSync().map(_.path) shouldBe Right("video_1.mp4")
      Files.exists(dir.resolve("video_1.mp4")) shouldBe true
    }

    "return a failure when the staged file is gone on commit" in {
      val store = LocalDirectoryStore(config(Files.createTempDirectory("amony-store-spec")))

      store.commit(StagingId("missing-upload"), "video.mp4").value.unsafeRunSync() shouldBe
        Left("Staged upload 'missing-upload' is no longer available")
    }

    "reject an absolute file name on commit" in {
      val store = LocalDirectoryStore(config(Files.createTempDirectory("amony-store-spec")))

      store.commit(StagingId("staged-upload"), "/abs.mp4").value.unsafeRunSync() shouldBe
        Left("Resource path must be relative: /abs.mp4")
    }

    "reject a bucket-escaping file name on commit" in {
      val store = LocalDirectoryStore(config(Files.createTempDirectory("amony-store-spec")))

      store.commit(StagingId("staged-upload"), "../other.mp4").value.unsafeRunSync() shouldBe
        Left("Resource path must not escape the bucket: ../other.mp4")
    }

    "return no content for an invalid path" in {
      val store = LocalDirectoryStore(config(Files.createTempDirectory("amony-store-spec")))

      store.content(resource("/abs.mp4"), None).unsafeRunSync() shouldBe None
    }

    "refuse to delete an invalid path" in {
      val store = LocalDirectoryStore(config(Files.createTempDirectory("amony-store-spec")))

      intercept[IllegalArgumentException] {
        store.delete(ResourcePath("../other.mp4")).unsafeRunSync()
      }
    }
  }

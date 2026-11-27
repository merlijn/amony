package nl.amony.modules.resources.impl

import java.nio.file.{Files, Path}
import java.util.UUID
import scala.concurrent.duration.*

import cats.effect.unsafe.implicits.global
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

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

  "LocalDirectoryStore" should {

    "return the content of an existing resource" in {
      val dir   = Files.createTempDirectory("amony-store-spec")
      Files.writeString(dir.resolve("video.mp4"), "content")
      val store = LocalDirectoryStore(config(dir))

      store.content(ResourcePath("video.mp4"), Some("video/mp4"), Some("etag-1")).unsafeRunSync() match
        case Some(ResourceContent.Streamed(streamed)) =>
          streamed.eTag shouldBe Some("etag-1")
          streamed.contentType shouldBe "video/mp4"
          streamed.stream.through(fs2.text.utf8.decode).compile.string.unsafeRunSync() shouldBe "content"
        case other                                    => fail(s"Expected streamed content, got $other")
    }

    "return no content when the object does not exist" in {
      val dir   = Files.createTempDirectory("amony-store-spec")
      val store = LocalDirectoryStore(config(dir))

      store.content(ResourcePath("missing.mp4"), Some("video/mp4"), None).unsafeRunSync() shouldBe None
    }

    "open a local media source for an existing resource" in {
      val dir   = Files.createTempDirectory("amony-store-spec")
      val file  = Files.writeString(dir.resolve("video.mp4"), "content")
      val store = LocalDirectoryStore(config(dir))

      store.openSource(ResourcePath("video.mp4")).unsafeRunSync() shouldBe Some(MediaSource.LocalFile(file))
      store.openSource(ResourcePath("missing.mp4")).unsafeRunSync() shouldBe None
      store.openSource(ResourcePath("../escape.mp4")).unsafeRunSync() shouldBe None
    }

    "stat an existing resource" in {
      val dir   = Files.createTempDirectory("amony-store-spec")
      Files.writeString(dir.resolve("video.mp4"), "content")
      val store = LocalDirectoryStore(config(dir))

      val stored = store.stat(ResourcePath("video.mp4")).unsafeRunSync()
      stored.map(_.path) shouldBe Some(ResourcePath("video.mp4"))
      stored.map(_.size) shouldBe Some(7L)
      store.stat(ResourcePath("missing.mp4")).unsafeRunSync() shouldBe None
    }

    "move a source file into the bucket on put" in {
      val dir    = Files.createTempDirectory("amony-store-spec")
      val cfg    = config(dir)
      val store  = LocalDirectoryStore(cfg)
      val source = Files.createTempFile("amony-staged", ".mp4")
      Files.writeString(source, "content")

      val stored = store.put("video.mp4", source).value.unsafeRunSync()

      stored.map(_.path) shouldBe Right(ResourcePath("video.mp4"))
      Files.exists(dir.resolve("video.mp4")) shouldBe true
      Files.exists(source) shouldBe false
    }

    "resolve a unique name when the target already exists on put" in {
      val dir    = Files.createTempDirectory("amony-store-spec")
      val cfg    = config(dir)
      val store  = LocalDirectoryStore(cfg)
      Files.writeString(dir.resolve("video.mp4"), "existing")
      val source = Files.createTempFile("amony-staged", ".mp4")
      Files.writeString(source, "content")

      store.put("video.mp4", source).value.unsafeRunSync().map(_.path) shouldBe Right(ResourcePath("video_1.mp4"))
      Files.exists(dir.resolve("video_1.mp4")) shouldBe true
    }

    "return a failure when the source file is gone on put" in {
      val store   = LocalDirectoryStore(config(Files.createTempDirectory("amony-store-spec")))
      val missing = Path.of("/nonexistent/missing-upload")

      store.put("video.mp4", missing).value.unsafeRunSync() shouldBe
        Left("Source file 'missing-upload' is no longer available")
    }

    "replace an existing resource atomically" in {
      val dir    = Files.createTempDirectory("amony-store-spec")
      val cfg    = config(dir)
      val store  = LocalDirectoryStore(cfg)
      Files.writeString(dir.resolve("video.mp4"), "old")
      val source = Files.createTempFile("amony-remux", ".mp4")
      Files.writeString(source, "new-content")

      val stored = store.replace(ResourcePath("video.mp4"), source).value.unsafeRunSync()

      stored.map(_.path) shouldBe Right(ResourcePath("video.mp4"))
      stored.map(_.size) shouldBe Right(11L)
      Files.readString(dir.resolve("video.mp4")) shouldBe "new-content"
      Files.exists(source) shouldBe false
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

    "reject an absolute file name on put" in {
      val store = LocalDirectoryStore(config(Files.createTempDirectory("amony-store-spec")))

      store.put("/abs.mp4", Path.of("staged")).value.unsafeRunSync() shouldBe
        Left("Resource path must be relative: /abs.mp4")
    }

    "reject a bucket-escaping file name on put" in {
      val store = LocalDirectoryStore(config(Files.createTempDirectory("amony-store-spec")))

      store.put("../other.mp4", Path.of("staged")).value.unsafeRunSync() shouldBe
        Left("Resource path must not escape the bucket: ../other.mp4")
    }

    "return no content for an invalid path" in {
      val store = LocalDirectoryStore(config(Files.createTempDirectory("amony-store-spec")))

      store.content(ResourcePath("/abs.mp4"), None, None).unsafeRunSync() shouldBe None
    }

    "refuse to delete an invalid path" in {
      val store = LocalDirectoryStore(config(Files.createTempDirectory("amony-store-spec")))

      intercept[IllegalArgumentException] {
        store.delete(ResourcePath("../other.mp4")).unsafeRunSync()
      }
    }
  }

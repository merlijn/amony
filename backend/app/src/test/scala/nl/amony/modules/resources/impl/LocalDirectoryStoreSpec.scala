package nl.amony.modules.resources.impl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.UUID
import scala.concurrent.duration.*

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fs2.Stream
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

      store.content(StoreKey.Resource(ResourcePath("video.mp4")), Some("video/mp4"), Some("etag-1")).unsafeRunSync() match
        case Some(ResourceContent.Streamed(streamed)) =>
          streamed.eTag shouldBe Some("etag-1")
          streamed.contentType shouldBe "video/mp4"
          streamed.stream.through(fs2.text.utf8.decode).compile.string.unsafeRunSync() shouldBe "content"
        case other                                    => fail(s"Expected streamed content, got $other")
    }

    "return no content when the object does not exist" in {
      val dir   = Files.createTempDirectory("amony-store-spec")
      val store = LocalDirectoryStore(config(dir))

      store.content(StoreKey.Resource(ResourcePath("missing.mp4")), Some("video/mp4"), None).unsafeRunSync() shouldBe None
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

    "stage and promote a file into the bucket" in {
      val dir       = Files.createTempDirectory("amony-store-spec")
      val store     = LocalDirectoryStore(config(dir))
      val stagingId = StagingId("resource-upload")

      store.put(
        StoreKey.Staged(stagingId),
        Some("video/mp4"),
        Stream.emits("content".getBytes(StandardCharsets.UTF_8))
      ).value.unsafeRunSync() shouldBe Right(())
      store.exists(StoreKey.Staged(stagingId)).unsafeRunSync() shouldBe true

      val stored = store.promote(stagingId, ResourcePath("video.mp4")).value.unsafeRunSync()

      stored.map(_.path) shouldBe Right(ResourcePath("video.mp4"))
      Files.exists(dir.resolve("video.mp4")) shouldBe true
      store.exists(StoreKey.Staged(stagingId)).unsafeRunSync() shouldBe false
    }

    "fail when the target already exists on promote with overwrite = false" in {
      val dir       = Files.createTempDirectory("amony-store-spec")
      val store     = LocalDirectoryStore(config(dir))
      Files.writeString(dir.resolve("video.mp4"), "existing")
      val stagingId = StagingId("resource-upload")

      store.put(
        StoreKey.Staged(stagingId),
        Some("video/mp4"),
        Stream.emits("content".getBytes(StandardCharsets.UTF_8))
      ).value.unsafeRunSync() shouldBe Right(())

      store.promote(stagingId, ResourcePath("video.mp4")).value.unsafeRunSync() shouldBe
        Left("Target file 'video.mp4' already exists")
      Files.readString(dir.resolve("video.mp4")) shouldBe "existing"
    }

    "return a failure when the staged file is gone on promote" in {
      val store = LocalDirectoryStore(config(Files.createTempDirectory("amony-store-spec")))

      store.promote(StagingId("missing-upload"), ResourcePath("video.mp4")).value.unsafeRunSync() shouldBe
        Left("Staged file 'missing-upload' is no longer available")
    }

    "replace an existing resource atomically on promote with overwrite = true" in {
      val dir       = Files.createTempDirectory("amony-store-spec")
      val store     = LocalDirectoryStore(config(dir))
      Files.writeString(dir.resolve("video.mp4"), "old")
      val stagingId = StagingId("remux-1")

      store.put(
        StoreKey.Staged(stagingId),
        Some("video/mp4"),
        Stream.emits("new-content".getBytes(StandardCharsets.UTF_8))
      ).value.unsafeRunSync() shouldBe Right(())
      val stored = store.promote(stagingId, ResourcePath("video.mp4"), overwrite = true).value.unsafeRunSync()

      stored.map(_.path) shouldBe Right(ResourcePath("video.mp4"))
      stored.map(_.size) shouldBe Right(11L)
      Files.readString(dir.resolve("video.mp4")) shouldBe "new-content"
      store.exists(StoreKey.Staged(stagingId)).unsafeRunSync() shouldBe false
    }

    "store and retrieve a derived file by key" in {
      val dir   = Files.createTempDirectory("amony-store-spec")
      val store = LocalDirectoryStore(config(dir))
      val key   = StoreKey.Derived(ResourceId("res1"), "res1_v1_w512.webp")

      store.put(key, Some("image/webp"), Stream.emits("image-bytes".getBytes(StandardCharsets.UTF_8))).value.unsafeRunSync() shouldBe Right(())

      store.content(key, Some("image/webp"), Some("etag-1")).unsafeRunSync() match
        case Some(ResourceContent.Streamed(streamed)) =>
          streamed.contentType shouldBe "image/webp"
          streamed.eTag shouldBe Some("etag-1")
          streamed.stream.through(fs2.text.utf8.decode).compile.string.unsafeRunSync() shouldBe "image-bytes"
        case other                                    => fail(s"Expected streamed content, got $other")
    }

    "store and retrieve in-memory derived bytes" in {
      val dir   = Files.createTempDirectory("amony-store-spec")
      val store = LocalDirectoryStore(config(dir))
      val key   = StoreKey.Derived(ResourceId("res1"), "res1_v1.vtt")

      store.putBytes(key, Some("text/vtt"), "WEBVTT\n".getBytes(StandardCharsets.UTF_8)).unsafeRunSync()

      store.content(key, Some("text/vtt"), None).unsafeRunSync() match
        case Some(ResourceContent.Streamed(streamed)) =>
          streamed.stream.through(fs2.text.utf8.decode).compile.string.unsafeRunSync() shouldBe "WEBVTT\n"
        case other                                    => fail(s"Expected streamed content, got $other")
    }

    "delete only derived files belonging to the given resource" in {
      val dir   = Files.createTempDirectory("amony-store-spec")
      val store = LocalDirectoryStore(config(dir))
      val keep  = StoreKey.Derived(ResourceId("res2"), "res2_v1_w512.webp")
      val del1  = StoreKey.Derived(ResourceId("res1"), "res1_v1_w512.webp")
      val del2  = StoreKey.Derived(ResourceId("res1"), "res1-legacy.webp")

      store.putBytes(keep, Some("image/webp"), "keep".getBytes(StandardCharsets.UTF_8)).unsafeRunSync()
      store.putBytes(del1, Some("image/webp"), "del1".getBytes(StandardCharsets.UTF_8)).unsafeRunSync()
      store.putBytes(del2, Some("image/webp"), "del2".getBytes(StandardCharsets.UTF_8)).unsafeRunSync()

      store.deleteDerived(ResourceId("res1")).unsafeRunSync()

      store.content(del1, Some("image/webp"), None).unsafeRunSync() shouldBe None
      store.content(del2, Some("image/webp"), None).unsafeRunSync() shouldBe None
      store.content(keep, Some("image/webp"), None).unsafeRunSync().isDefined shouldBe true
    }

    "delete a stored resource" in {
      val dir   = Files.createTempDirectory("amony-store-spec")
      val file  = Files.writeString(dir.resolve("video.mp4"), "content")
      val store = LocalDirectoryStore(config(dir))

      store.delete(StoreKey.Resource(ResourcePath("video.mp4"))).unsafeRunSync()

      Files.exists(file) shouldBe false
    }

    "ignore a missing resource on delete" in {
      val store = LocalDirectoryStore(config(Files.createTempDirectory("amony-store-spec")))

      store.delete(StoreKey.Resource(ResourcePath("missing.mp4"))).unsafeRunSync()
    }

    "reject an absolute file name on promote" in {
      val store = LocalDirectoryStore(config(Files.createTempDirectory("amony-store-spec")))

      store.promote(StagingId("staged"), ResourcePath("/abs.mp4")).value.unsafeRunSync() shouldBe
        Left("Resource path must be relative: /abs.mp4")
    }

    "reject a bucket-escaping file name on promote" in {
      val store = LocalDirectoryStore(config(Files.createTempDirectory("amony-store-spec")))

      store.promote(StagingId("staged"), ResourcePath("../other.mp4")).value.unsafeRunSync() shouldBe
        Left("Resource path must not escape the bucket: ../other.mp4")
    }

    "return no content for an invalid path" in {
      val store = LocalDirectoryStore(config(Files.createTempDirectory("amony-store-spec")))

      store.content(StoreKey.Resource(ResourcePath("/abs.mp4")), None, None).unsafeRunSync() shouldBe None
    }

    "refuse to delete an invalid path" in {
      val store = LocalDirectoryStore(config(Files.createTempDirectory("amony-store-spec")))

      intercept[IllegalArgumentException] {
        store.delete(StoreKey.Resource(ResourcePath("../other.mp4"))).unsafeRunSync()
      }
    }
  }

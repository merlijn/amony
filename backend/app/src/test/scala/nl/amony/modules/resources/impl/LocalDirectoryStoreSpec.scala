package nl.amony.modules.resources.impl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*

import cats.effect.unsafe.implicits.global
import fs2.Stream
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import nl.amony.modules.resources.api.*

class LocalDirectoryStoreSpec extends AnyWordSpecLike with Matchers:

  private def config(dir: Path): LocalDirectoryStorageConfig =
    LocalDirectoryStorageConfig(
      path               = dir,
      sync               = ScanConfig(
        enabled         = false,
        syncOnStartup   = false,
        newFilesOwner   = "admin",
        pollInterval    = 60.seconds,
        includePatterns = List("**/*"),
        excludePatterns = Nil
      ),
      relativeUploadPath = Path.of("_upload")
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

    "write and atomically replace a resource via put" in {
      val dir   = Files.createTempDirectory("amony-store-spec")
      val store = LocalDirectoryStore(config(dir))
      val key   = StoreKey.Resource(ResourcePath("video.mp4"))

      store.put(key, Some("video/mp4"), Stream.emits("v1".getBytes(StandardCharsets.UTF_8))).value.unsafeRunSync() shouldBe Right(())
      Files.readString(dir.resolve("video.mp4")) shouldBe "v1"

      store.put(key, Some("video/mp4"), Stream.emits("v2-longer".getBytes(StandardCharsets.UTF_8))).value.unsafeRunSync() shouldBe Right(())
      Files.readString(dir.resolve("video.mp4")) shouldBe "v2-longer"
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

    "reject invalid paths on put, content and delete" in {
      val store = LocalDirectoryStore(config(Files.createTempDirectory("amony-store-spec")))

      store.put(StoreKey.Resource(ResourcePath("/abs.mp4")), None, Stream.empty).value.unsafeRunSync() shouldBe
        Left("Resource path must be relative: /abs.mp4")
      store.put(StoreKey.Resource(ResourcePath("../other.mp4")), None, Stream.empty).value.unsafeRunSync() shouldBe
        Left("Resource path must not escape the bucket: ../other.mp4")
      store.content(StoreKey.Resource(ResourcePath("/abs.mp4")), None, None).unsafeRunSync() shouldBe None
      intercept[IllegalArgumentException] {
        store.delete(StoreKey.Resource(ResourcePath("../other.mp4"))).unsafeRunSync()
      }
    }

    "derive human-friendly unique upload paths and keep paths in place on replacement" in {
      val store = LocalDirectoryStore(config(Files.createTempDirectory("amony-store-spec")))
      val id    = ResourceId("ABCDEFGHIJKLMNOPQRSTUVWX")

      store.pathForUpload(id, "video.mp4") shouldBe ResourcePath(s"video [$id].mp4")
      store.pathForUpload(id, "my.holiday.clip.mp4") shouldBe ResourcePath(s"my.holiday.clip [$id].mp4")
      store.pathForUpload(id, "README") shouldBe ResourcePath(s"README [$id]")
      store.pathForUpload(id, ".hidden") shouldBe ResourcePath(s".hidden [$id]")
      store.pathForUpload(id, "trailing.") shouldBe ResourcePath(s"trailing. [$id]")

      val longAscii = ("a" * 300) + ".mp4"
      val asciiPath = store.pathForUpload(id, longAscii)
      asciiPath.getBytes("UTF-8").length shouldBe 255
      asciiPath should endWith(s" [$id].mp4")

      val longEmoji = ("🎉" * 100) + ".mp4"
      val emojiPath = store.pathForUpload(id, longEmoji)
      emojiPath.getBytes("UTF-8").length should be <= 255
      emojiPath should endWith(s" [$id].mp4")
      emojiPath.codePoints().count() should be > 0L

      store.pathForReplacement(ResourcePath("nested/video.mp4"), id) shouldBe ResourcePath("nested/video.mp4")
    }
  }

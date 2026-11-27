package nl.amony.modules.resources.impl

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import cats.effect.unsafe.implicits.global
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import nl.amony.modules.resources.api.{ResourceContent, ResourceId}

class LocalDerivedStoreSpec extends AnyWordSpecLike with Matchers:

  "LocalDerivedStore" should {

    "store and retrieve a derived file by key" in {
      val cacheDir = Files.createTempDirectory("amony-derived-spec")
      val store    = LocalDerivedStore(cacheDir)
      val source   = Files.createTempFile("amony-derived-src", ".webp")
      Files.writeString(source, "image-bytes")
      val key      = DerivedKey(ResourceId("res1"), "res1_v1_w512.webp")

      store.put(key, "image/webp", source).unsafeRunSync()

      Files.exists(source) shouldBe false
      store.get(key, "image/webp", Some("etag-1")).unsafeRunSync() match
        case Some(ResourceContent.Streamed(streamed)) =>
          streamed.contentType shouldBe "image/webp"
          streamed.eTag shouldBe Some("etag-1")
          streamed.stream.through(fs2.text.utf8.decode).compile.string.unsafeRunSync() shouldBe "image-bytes"
        case other                                    => fail(s"Expected streamed content, got $other")
    }

    "store and retrieve in-memory bytes" in {
      val cacheDir = Files.createTempDirectory("amony-derived-spec")
      val store    = LocalDerivedStore(cacheDir)
      val key      = DerivedKey(ResourceId("res1"), "res1_v1.vtt")

      store.putBytes(key, "text/vtt", "WEBVTT\n".getBytes(StandardCharsets.UTF_8)).unsafeRunSync()

      store.get(key, "text/vtt", None).unsafeRunSync() match
        case Some(ResourceContent.Streamed(streamed)) =>
          streamed.stream.through(fs2.text.utf8.decode).compile.string.unsafeRunSync() shouldBe "WEBVTT\n"
        case other                                    => fail(s"Expected streamed content, got $other")
    }

    "delete only derived files belonging to the given resource" in {
      val cacheDir = Files.createTempDirectory("amony-derived-spec")
      val store    = LocalDerivedStore(cacheDir)
      val keep     = DerivedKey(ResourceId("res2"), "res2_v1_w512.webp")
      val del1     = DerivedKey(ResourceId("res1"), "res1_v1_w512.webp")
      val del2     = DerivedKey(ResourceId("res1"), "res1-legacy.webp")

      store.putBytes(keep, "image/webp", "keep".getBytes(StandardCharsets.UTF_8)).unsafeRunSync()
      store.putBytes(del1, "image/webp", "del1".getBytes(StandardCharsets.UTF_8)).unsafeRunSync()
      store.putBytes(del2, "image/webp", "del2".getBytes(StandardCharsets.UTF_8)).unsafeRunSync()

      store.deleteForResource(ResourceId("res1")).unsafeRunSync()

      store.get(del1, "image/webp", None).unsafeRunSync() shouldBe None
      store.get(del2, "image/webp", None).unsafeRunSync() shouldBe None
      store.get(keep, "image/webp", None).unsafeRunSync().isDefined shouldBe true
    }
  }

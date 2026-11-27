package nl.amony.modules.resources.impl

import java.nio.file.Files

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import nl.amony.modules.resources.api.StagingId

class LocalStagingAreaSpec extends AnyWordSpecLike with Matchers:

  "LocalStagingArea" should {

    "write, get and delete a staged file" in {
      val dir     = Files.createTempDirectory("amony-staging-spec")
      val staging = LocalStagingArea(dir)
      val id      = StagingId("upload-1")

      val written = staging.write(id, fs2.Stream.emits("hello".getBytes).covary[IO]).unsafeRunSync()
      written.id shouldBe id
      written.size shouldBe 5L
      Files.readString(written.path) shouldBe "hello"

      staging.get(id).unsafeRunSync() shouldBe Some(written)

      staging.delete(id).unsafeRunSync()
      staging.get(id).unsafeRunSync() shouldBe None
      Files.exists(written.path) shouldBe false
    }

    "clean up a partial file when streaming fails" in {
      val dir     = Files.createTempDirectory("amony-staging-spec")
      val staging = LocalStagingArea(dir)
      val id      = StagingId("upload-fail")
      val failing = fs2.Stream.emits("part".getBytes).covary[IO] ++ fs2.Stream.raiseError[IO](new RuntimeException("boom"))

      intercept[RuntimeException] {
        staging.write(id, failing).unsafeRunSync()
      }

      staging.get(id).unsafeRunSync() shouldBe None
    }

    "delete temporary files on release" in {
      val dir     = Files.createTempDirectory("amony-staging-spec")
      val staging = LocalStagingArea(dir)

      val createdPath = staging.tempFile("preview", ".webp").use { path =>
        IO.blocking(Files.writeString(path, "data")).as(path)
      }.unsafeRunSync()

      Files.exists(createdPath) shouldBe false
    }
  }

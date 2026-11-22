package nl.amony.modules.resources.impl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import cats.effect.unsafe.implicits.global
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import nl.amony.modules.resources.api.ResourceId

class PartialHashMigrationSpec extends AnyWordSpec with Matchers {

  private val resourceId = ResourceId("resource")

  private def cacheWith(name: String, content: String): (Path, Path) =
    val cache = Files.createTempDirectory("partial-hash-migration")
    val file  = cache.resolve(name)
    Files.writeString(file, content, StandardCharsets.UTF_8)
    (cache, file)

  private def rename(cache: Path): Unit =
    val update = PartialHashMigration.HashUpdate(resourceId, "new-hash", Set("oldversion"), "newversion")
    PartialHashMigration.renameDerivedFiles(cache, List(update)).unsafeRunSync()

  "PartialHashMigration.renameDerivedFiles" should {

    "rename files for the resource and update manifest contents" in {
      val (cache, thumbnail) = cacheWith("resource_oldversion_1000_w640.avif", "thumbnail")
      val sprite             = cache.resolve("resource_oldversion_tile_2000ms_p20480_token_1.webp")
      val manifest           = cache.resolve("resource_oldversion_tile_2000ms_p20480_token.vtt")
      val other              = cache.resolve("other_oldversion_1000_w640.avif")
      Files.writeString(sprite, "sprite", StandardCharsets.UTF_8)
      Files.writeString(
        manifest,
        "WEBVTT\n\nthumbtile_resource_oldversion_token_1.webp#xywh=0,0,1,1\n",
        StandardCharsets.UTF_8
      )
      Files.writeString(other, "other", StandardCharsets.UTF_8)

      rename(cache)

      Files.exists(thumbnail) shouldBe false
      Files.exists(sprite) shouldBe false
      Files.exists(manifest) shouldBe false
      Files.exists(cache.resolve("resource_newversion_1000_w640.avif")) shouldBe true
      Files.exists(cache.resolve("resource_newversion_tile_2000ms_p20480_token_1.webp")) shouldBe true
      Files.readString(cache.resolve("resource_newversion_tile_2000ms_p20480_token.vtt")) should include("resource_newversion")
      Files.exists(other) shouldBe true
    }

    "be idempotent" in {
      val (cache, _) = cacheWith("resource_oldversion_1000_w640.avif", "thumbnail")

      rename(cache)
      rename(cache)

      Files.exists(cache.resolve("resource_newversion_1000_w640.avif")) shouldBe true
    }
  }
}

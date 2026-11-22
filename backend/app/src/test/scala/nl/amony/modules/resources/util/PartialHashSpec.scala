package nl.amony.modules.resources.util

import java.nio.file.{Files, Path}
import java.security.MessageDigest

import cats.effect.unsafe.implicits.global
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import nl.amony.modules.resources.api.PartialHash as PartialHashAlgorithm

class PartialHashSpec extends AnyWordSpec with Matchers {

  private val recipeLength = PartialHash.Recipe.length + 1 + 24

  private def hash(bytes: Array[Byte]): String =
    val path = Files.createTempFile("partial-hash-test", ".bin")
    try
      Files.write(path, bytes)
      PartialHash.partialHash(path, () => MessageDigest.getInstance("SHA-256"), PartialHash.encodeHash).unsafeRunSync()
    finally Files.deleteIfExists(path)

  private def hashFile(path: Path): String =
    PartialHashAlgorithm.createHash(path).unsafeRunSync()

  private def largeFile(fill: Byte, size: Int = 64 * 1024): Path =
    val path = Files.createTempFile("partial-hash-large", ".bin")
    Files.write(path, Array.fill(size)(fill))
    path

  "PartialHash" should {

    "produce a fixed-length, versioned hash" in {
      val encoded = hash(Array.emptyByteArray)
      encoded should startWith(PartialHash.Prefix)
      encoded.length shouldBe recipeLength
    }

    "hash empty files" in {
      hash(Array.emptyByteArray) should not be empty
    }

    "hash files smaller than a single chunk" in {
      hash(Array.fill(10)(1.toByte)) should not equal hash(Array.fill(10)(2.toByte))
    }

    "hash files of exactly one chunk" in {
      hash(Array.fill(32)(2.toByte)) should not be empty
    }

    "hash files smaller than the total sample size" in {
      hash(Array.fill(1000)(3.toByte)) should not be empty
    }

    "distinguish different small files" in {
      hash(Array.fill(10)(1.toByte)) should not equal hash(Array.fill(10)(2.toByte))
    }

    "be deterministic for identical content" in {
      val content = Array.tabulate(4096)(_.toByte)
      hash(content) shouldBe hash(content.clone())
    }

    "hash larger files by sampling" in {
      hash(Array.fill(4096)(4.toByte)) should not be empty
    }

    "always include the first byte" in {
      val file = largeFile(0)
      try
        val changed = file.resolveSibling(file.getFileName.toString + ".changed")
        val bytes   = Files.readAllBytes(file)
        bytes(0) = 1
        Files.write(changed, bytes)
        try hashFile(file) should not equal hashFile(changed)
        finally Files.deleteIfExists(changed)
      finally Files.deleteIfExists(file)
    }

    "always include the last byte" in {
      val file = largeFile(0)
      try
        val changed = file.resolveSibling(file.getFileName.toString + ".changed")
        val bytes   = Files.readAllBytes(file)
        bytes(bytes.length - 1) = 1
        Files.write(changed, bytes)
        try hashFile(file) should not equal hashFile(changed)
        finally Files.deleteIfExists(changed)
      finally Files.deleteIfExists(file)
    }

    "sample the middle of large files" in {
      val file = largeFile(0)
      try
        val changed = file.resolveSibling(file.getFileName.toString + ".changed")
        val bytes   = Files.readAllBytes(file)
        java.util.Arrays.fill(bytes, bytes.length / 4, bytes.length * 3 / 4, 1.toByte)
        Files.write(changed, bytes)
        try hashFile(file) should not equal hashFile(changed)
        finally Files.deleteIfExists(changed)
      finally Files.deleteIfExists(file)
    }
  }
}

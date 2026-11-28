package nl.amony.modules.resources.util

import java.io.RandomAccessFile
import java.nio.file.{Files, Path}
import scala.util.Using

import cats.effect.unsafe.implicits.global
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class PartialHashSpec extends AnyWordSpecLike with Matchers {

  private val recipeLength = PartialHash.Recipe.length + 1 + 24

  private def hash(bytes: Array[Byte]): String =
    val path = Files.createTempFile("partial-hash-test", ".bin")
    try
      Files.write(path, bytes)
      hashFile(path)
    finally Files.deleteIfExists(path)

  private def hashFile(path: Path): String =
    PartialHash.createHash(path).unsafeRunSync()

  private def largeFile(fill: Byte, size: Int = 64 * 1024): Path =
    val path = Files.createTempFile("partial-hash-large", ".bin")
    Files.write(path, Array.fill(size)(fill))
    path

  "PartialHash" when {

    "hashing a file" should {

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
        hash(Array.fill(64 * 1024)(4.toByte)) should not be empty
      }

      "distinguish file sizes even when all sampled bytes are identical" in {
        val size = 64 * 1024
        hash(new Array[Byte](size)) should not equal hash(new Array[Byte](size + 1))
      }
    }

    "sampling the file" should {

      "center the sample within each middle bucket" in {
        val size       = 64 * 1024
        val firstStart = 8 * 1024 + 128
        val lastStart  = size - 8 * 1024 - 256
        val unchanged  = hash(new Array[Byte](size))

        def hashWithByteChanged(offset: Int): String =
          val content = new Array[Byte](size)
          content(offset) = 1
          hash(content)

        for start <- List(firstStart, lastStart) do
          hashWithByteChanged(start - 1) shouldBe unchanged
          hashWithByteChanged(start) should not equal unchanged
          hashWithByteChanged(start + 127) should not equal unchanged
          hashWithByteChanged(start + 128) shouldBe unchanged
      }

      "sample at offsets beyond 32 bits" in {
        val file = Files.createTempFile("partial-hash-sparse", ".bin")
        val size = 5L * 1024 * 1024 * 1024 + 16 * 1024
        try
          Using.resource(new RandomAccessFile(file.toFile, "rw"))(_.setLength(size))
          val unchanged = hashFile(file)

          Using.resource(new RandomAccessFile(file.toFile, "rw")) { sparse =>
            val lastBucketCenter = size - 8 * 1024 - 20 * 1024 * 1024
            sparse.seek(lastBucketCenter)
            sparse.write(1)
          }

          hashFile(file) should not equal unchanged
        finally Files.deleteIfExists(file)
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

    "matching the ph1 recipe" should {

      "match the ph1 recipe at the full-read cutoff and with uneven middle buckets" in {
        val vectors = List(
          32768 -> "ph1_236gg3luzocicue3kwvlqbl7",
          32769 -> "ph1_fyr6msdeifvjapwzfccrx3hi",
          65601 -> "ph1_t56s2k46ognlxr2jiwuaojrt"
        )

        for (size, expected) <- vectors do
          val content = Array.tabulate(size)(i => (i ^ (i >>> 7) ^ (i >>> 13)).toByte)
          withClue(s"File size $size: ")(hash(content) shouldBe expected)
      }
    }
  }
}

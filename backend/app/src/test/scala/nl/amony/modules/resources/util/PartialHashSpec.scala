package nl.amony.modules.resources.util

import java.nio.file.Files
import java.security.MessageDigest

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class PartialHashSpec extends AnyWordSpec with Matchers {

  def hashFile(bytes: Array[Byte]): String =
    val path = Files.createTempFile("partial-hash-test", ".bin")
    try {
      Files.write(path, bytes)
      PartialHash.partialHash(path, nChunks = 32, chunkSize = 32, () => MessageDigest.getInstance("SHA-1"), Base32.encode).unsafeRunSync()
    } finally Files.deleteIfExists(path)

  "PartialHash" should {
    "hash empty files" in {
      hashFile(Array.emptyByteArray) should not be empty
    }

    "hash files smaller than a single chunk" in {
      hashFile(Array.fill(10)(1.toByte)) should not be empty
    }

    "hash files of exactly one chunk" in {
      hashFile(Array.fill(32)(2.toByte)) should not be empty
    }

    "hash files smaller than the total sample size" in {
      hashFile(Array.fill(1000)(3.toByte)) should not be empty
    }

    "distinguish different small files" in {
      hashFile(Array.fill(10)(1.toByte)) should not equal hashFile(Array.fill(10)(2.toByte))
    }

    "hash larger files by sampling" in {
      hashFile(Array.fill(4096)(4.toByte)) should not be empty
    }
  }
}

package nl.amony.modules.resources.util

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import java.security.MessageDigest
import scala.util.Using

import cats.effect.IO

/**
 * Content hash based on a deterministic sample of a file: two fixed windows at the start and end plus chunks spread
 * evenly over the middle. The recipe version is mixed into the digest and prefixed to the encoded value, so hashes from
 * different recipes can never compare equal.
 */
object PartialHash {

  /** Hash recipe version, mixed into the digest and used as the encoded prefix. */
  val Recipe: String = "ph1"

  val Prefix: String = s"${Recipe}_"

  private val HashBytes = 15 // 120 bits = 24 Base32 characters

  val HeadBytes: Int    = 8 * 1024
  val TailBytes: Int    = 8 * 1024
  val MiddleBytes: Int  = 16 * 1024
  val MiddleChunks: Int = 128

  def encodeHash(bytes: Array[Byte]): String =
    Prefix + Base32.encode(java.util.Arrays.copyOf(bytes, HashBytes))

  /**
   * Hashes a deterministic sample of the file.
   *
   * Files no larger than the total sample size are hashed in full. Larger files contribute their head, their tail and
   * one centered chunk per middle bucket. Bucket boundaries partition the whole middle region.
   */
  def partialHash(
    file: Path,
    digestFn: () => MessageDigest,
    encoder: Array[Byte] => String,
    headBytes: Int    = HeadBytes,
    tailBytes: Int    = TailBytes,
    middleBytes: Int  = MiddleBytes,
    middleChunks: Int = MiddleChunks
  ): IO[String] =
    IO.blocking {
      require(headBytes >= 0, s"headBytes must be >= 0, got $headBytes")
      require(tailBytes >= 0, s"tailBytes must be >= 0, got $tailBytes")
      require(middleBytes >= 0, s"middleBytes must be >= 0, got $middleBytes")
      require(middleChunks > 0, s"middleChunks must be > 0, got $middleChunks")
      require(middleBytes >= middleChunks, s"middleBytes must be at least middleChunks, got $middleBytes and $middleChunks")

      val size   = Files.size(file)
      val digest = digestFn()

      digest.update(Recipe.getBytes(StandardCharsets.UTF_8))
      digest.update(ByteBuffer.allocate(java.lang.Long.BYTES).putLong(size).array())
      sampleIntoDigest(file, size, digest, headBytes, tailBytes, middleBytes, middleChunks)

      encoder(digest.digest())
    }

  private def sampleIntoDigest(
    file: Path,
    size: Long,
    digest: MessageDigest,
    headBytes: Int,
    tailBytes: Int,
    middleBytes: Int,
    middleChunks: Int
  ): Unit =
    Using.resource(FileChannel.open(file, StandardOpenOption.READ)) { channel =>
      if size <= headBytes.toLong + tailBytes.toLong + middleBytes.toLong then {
        val all = ByteBuffer.allocate(size.toInt)
        readFully(channel, all, 0L)
        digest.update(all.array())
      } else {
        val head = ByteBuffer.allocate(headBytes)
        readFully(channel, head, 0L)
        digest.update(head.array())

        val tail = ByteBuffer.allocate(tailBytes)
        readFully(channel, tail, size - tailBytes)
        digest.update(tail.array())

        val middleLength = size - headBytes - tailBytes
        val chunkSize    = math.max(1, middleBytes / middleChunks)
        val bucketSize   = middleLength / middleChunks
        val remainder    = middleLength % middleChunks
        val chunk        = ByteBuffer.allocate(chunkSize)

        def bucketBoundary(index: Long): Long = index * bucketSize + index * remainder / middleChunks

        var index = 0
        while index < middleChunks do {
          val start  = bucketBoundary(index.toLong)
          val end    = bucketBoundary(index.toLong + 1)
          val offset = headBytes.toLong + start + (end - start - chunkSize) / 2

          chunk.clear()
          readFully(channel, chunk, offset)
          digest.update(chunk.array())
          index += 1
        }
      }
    }

  private def readFully(channel: FileChannel, buffer: ByteBuffer, position: Long): Unit = {
    var pos = position
    while buffer.hasRemaining do {
      val read = channel.read(buffer, pos)
      if read < 0 then throw new java.io.EOFException(s"Unexpected end of file at byte $pos")
      else if read == 0 then throw new java.io.IOException(s"Read returned no bytes at byte $pos")
      else pos += read
    }
    buffer.flip()
  }
}

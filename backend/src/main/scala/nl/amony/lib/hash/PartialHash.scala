package nl.amony.lib.hash

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{Path, StandardOpenOption}
import java.security.MessageDigest
import java.util
import scala.util.{Random, Using}

import cats.effect.IO
import scribe.Logging

import nl.amony.lib.files.*

object PartialHash extends Logging {

  /**
   * Randomly samples given number of bytes from a file.
   *
   * @param file   The file in question
   * @param nChunks The number of bytes to sample
   * @return
   */
  def sampleBytesFromFile(file: Path, nChunks: Int, chunkSize: Int = 1): Array[Byte] = {
    val size   = file.size()
    val result = new Array[Byte](nChunks * chunkSize)

    // we pass the length/size of the file as seed
    val random = new Random(size)

    Using(FileChannel.open(file, StandardOpenOption.READ)) {
      channel =>
        (0 until nChunks).map {
          i =>

            val pos = random.nextLong(size - chunkSize)

            try {
              val buffer = ByteBuffer.allocate(chunkSize)
              channel.position(pos)
              channel.read(buffer)
              buffer.array().copyToArray(result, i * chunkSize, chunkSize)
            } catch {
              case e: Exception =>
                logger.warn(s"Failed reading byte at: $file ($size): $i, position: $pos")
                throw e;
            }
        }
    }

    result
  }

  // samples a file randomly and creates a hash from that
  def partialHash(file: Path, nChunks: Int = 32, chunkSize: Int = 32, digestFn: () => MessageDigest, encoder: Array[Byte] => String): IO[String] =
    IO {

      val bytes = sampleBytesFromFile(file, nChunks, chunkSize)

      val digest      = digestFn()
      val hash        = digest.digest(bytes)
      val encodedHash = encoder(hash)

      encodedHash
    }
}

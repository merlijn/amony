package nl.amony.lib.hash

import nl.amony.lib.files.PathOps
import scribe.Logging

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{Path, StandardOpenOption}
import java.security.MessageDigest
import scala.util.{Random, Using}

object PartialHash extends Logging {

  val blockSize = 4096

  /**
   * Randomly samples given number of bytes from a file.
   *
   * @param file   The file in question
   * @param nBytes The number of bytes to sample
   * @return
   */
  def sampleBytesFromFile(file: Path, nBytes: Int): Array[Byte] = {
    val size   = file.size()
    val bytes  = new Array[Byte](nBytes)

    // TODO What if the Random implementation from jvm changes and returns a different result?
    // we pass the length/size of the file as seed
    val random = new Random(size)

    Using(FileChannel.open(file, StandardOpenOption.READ)) { channel =>
      (0 until nBytes).map { i =>
        val pos = (random.nextDouble() * (size - 1)).toLong
        try {
          val buffer = ByteBuffer.allocate(1)
          channel.position(pos)
          channel.read(buffer)
          bytes(i) = buffer.get(0)
        } catch {
          case _: Exception =>
            logger.warn(s"Failed reading byte at: ${file} ($size): $i, $pos")
        }
      }
    }

    bytes
  }

  // samples a file randomly and creates a hash from that
  def partialHash(file: Path, nBytes: Int, hasher: () => MessageDigest, encoder: Array[Byte] => String): String = {

    val bytes = sampleBytesFromFile(file, nBytes)

    val digest = hasher()
    val hash = digest.digest(bytes)
    val encodedHash = encoder(hash)

    encodedHash
  }
}

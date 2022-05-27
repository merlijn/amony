package nl.amony.lib.hash

import nl.amony.lib.files.PathOps
import scribe.Logging

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{Path, StandardOpenOption}
import scala.util.{Random, Using}

object PartialHash extends Logging {

  // samples a file randomly and creates a hash from that
  def partialHash(file: Path, nBytes: Int = 512, hasher: Array[Byte] => String): String = {
    def readRandomBytes(): Array[Byte] = {

      val size   = file.size()
      val bytes  = new Array[Byte](nBytes)
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

    val bytes = readRandomBytes()

    hasher(bytes)
  }
}

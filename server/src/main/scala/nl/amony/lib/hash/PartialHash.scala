package nl.amony.lib.hash

import better.files.File
import scribe.Logging

import scala.util.Random

object PartialHash extends Logging {

  // samples a file randomly and creates a hash from that
  def partialHash(file: File, nBytes: Int = 512, hasher: Array[Byte] => String): String = {
    def readRandomBytes(): Array[Byte] = {

      val size   = file.size
      val bytes  = new Array[Byte](nBytes)
      val random = new Random(size)

      file.randomAccess().foreach { rndAccess =>
        (0 until nBytes).map { i =>
          val pos = (random.nextDouble() * (size - 1)).toLong
          try {
            rndAccess.seek(pos)
            bytes(i) = rndAccess.readByte()
          } catch {
            case _: Exception =>
              logger.warn(s"Failed reading byte at: ${file.name} ($size): $i, $pos")
          }
        }
      }

      bytes
    }

    val bytes = readRandomBytes()

    hasher(bytes)
  }
}

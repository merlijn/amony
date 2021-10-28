package nl.amony.lib

import java.io.ByteArrayOutputStream


/**
 * A Base62 encoder/decoder.
 *
 * Adapted from https://github.com/seruco/base62
 */
object Base62 {

  private val STANDARD_BASE = 256 // 2^8
  private val TARGET_BASE = 62

  def createInstance(): Base62 = new Base62(CharacterSets.GMP)

  object CharacterSets {
    val GMP: Array[Byte] = List(
      '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L',
      'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z', 'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h',
      'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z').map(_.toByte).toArray

    val INVERTED: Array[Byte] = List(
      '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l',
      'm', 'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H',
      'I', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z').map(_.toByte).toArray
  }
}

class Base62 private(val alphabet: Array[Byte]) {

  private val lookup: Array[Byte] =  {
    val tmp = new Array[Byte](256)
    var i = 0
    while ( { i < alphabet.length }) {
      tmp(alphabet(i)) = (i & 0xFF).toByte
      i += 1
    }
    tmp
  }

  def encodeToString(msg: Array[Byte]): String = new String(encode(msg))

  def encodeToString(string: String): String = new String(encode(string.getBytes))

  /**
   * Encodes a sequence of bytes in Base62 encoding.
   *
   * @param message a byte sequence.
   * @return a sequence of Base62-encoded bytes.
   */
  def encode(message: Array[Byte]): Array[Byte] =
    translate(convert(message, Base62.STANDARD_BASE, Base62.TARGET_BASE), alphabet)

  /**
   * Decodes a sequence of Base62-encoded bytes.
   *
   * @param encoded a sequence of Base62-encoded bytes.
   * @return a byte sequence.
   * @throws IllegalArgumentException when {@code encoded} is not encoded over the Base62 alphabet.
   */
  def decode(encoded: Array[Byte]): Array[Byte] = {
    if (!isBase62Encoded(encoded)) throw new IllegalArgumentException("Input is not encoded correctly")
    convert(translate(encoded, lookup), Base62.TARGET_BASE, Base62.STANDARD_BASE)
  }

  /**
   * Checks whether a sequence of bytes is encoded over a Base62 alphabet.
   *
   * @param bytes a sequence of bytes.
   * @return {@code true} when the bytes are encoded over a Base62 alphabet, {@code false} otherwise.
   */
  def isBase62Encoded(bytes: Array[Byte]): Boolean =
    !bytes.exists(e => ('0' > e || '9' < e) && ('a' > e || 'z' < e) && ('A' > e || 'Z' < e))

  /**
   * Uses the elements of a byte array as indices to a dictionary and returns the corresponding values
   * in form of a byte array.
   */
  private def translate(indices: Array[Byte], dictionary: Array[Byte]): Array[Byte] = indices.map(i => dictionary(i))

  /**
   *
   * This algorithm is inspired by: http://codegolf.stackexchange.com/a/21672
   * Converts a byte array from a source base to a target base using the alphabet.
   */
  private def convert(message: Array[Byte], sourceBase: Int, targetBase: Int): Array[Byte] = {

    val estimatedLength = Math.ceil((Math.log(sourceBase) / Math.log(targetBase)) * message.length).toInt

    val out = new ByteArrayOutputStream(estimatedLength)
    var source = message

    while ( {source.length > 0 }) {
      val quotient = new ByteArrayOutputStream(source.length)
      var remainder = 0
      for (i <- 0 until source.length) {
        val accumulator = (source(i) & 0xFF) + remainder * sourceBase
        val digit = (accumulator - (accumulator % targetBase)) / targetBase
        remainder = accumulator % targetBase
        if (quotient.size > 0 || digit > 0) quotient.write(digit)
      }
      out.write(remainder)
      source = quotient.toByteArray
    }
    // pad output with zeroes corresponding to the number of leading zeroes in the message
    var i = 0
    while ( { i < message.length - 1 && message(i) == 0 }) {
      out.write(0)
      i += 1
    }

    out.toByteArray.reverse
  }
}

package nl.amony.service.resources.util

object Base32 {

  val alphabet = "abcdefghijklmnopqrstuvwxyz234567".toCharArray

  val byteN = 8

  // 2^5 = 32
  val baseN = 5

  /** Returns n bits in the byte at pos shifted all the way to right
    *
    * For example:
    *
    * b = 00110100, start = 2, end = 6, you want to extract 00|1101|00, result: 0000|1101|
    *
    * @param b The byte
    * @param start The start index (inclusive)
    * @param end The end index (exclusive)
    * @return
    */
  private def bitsAt(b: Byte, start: Int, end: Int): Int =
    (b >> (8 - end)) & (0xff >> (8 - end + start))

  /**
   * This currently works as is. Code looks a bit clunky and maybe inefficient though.
   *
   * @param bytes
   * @return
   */
  def encode2(bytes: Array[Byte]): String = {

    // calculate the size of the string
    val size          = Math.ceil((byteN * bytes.length) / baseN.toDouble).toInt
    val stringBuilder = new StringBuilder(size)

    (0 until size).foreach { n =>
      val m: Int = baseN * n
      val index: Int  = m / byteN
      val offset: Int = m % byteN

      val idx = {
        // 5 bit chunk fits in current byte
        if (offset + baseN <= byteN) {
          bitsAt(bytes(index), offset, offset + baseN)
        // 5 bit chunk is divided over 2 bytes
        } else if (index < bytes.length - 1) {
          val n2 = offset - (byteN - baseN) + 1
          val c1 = bitsAt(bytes(index), offset, byteN)
          val c2 = bitsAt(bytes(index + 1), 0, n2)

          (c1 << (n2 - 1)) | c2
        // last chunk
        } else {
          bitsAt(bytes(index), offset, byteN)
        }
      }

      stringBuilder.addOne(alphabet(idx & 0xff))
    }

    stringBuilder.toString()
  }

  def encode(bytes: Array[Byte]): String = {
    val totalBits = bytes.length * 8
    var bitIndex = 0
    val sb = new StringBuilder

    while (bitIndex < totalBits) {
      val bitsToRead = math.min(5, totalBits - bitIndex)
      val bits = extractBits(bytes, bitIndex, bitsToRead)
      val c = alphabet(bits)
      sb.append(c)
      bitIndex += bitsToRead
    }
    sb.toString
  }

//  def extractBit(bytes: Array[Byte], bitIndex: Int): Int = {
//    val byteIndex = bitIndex / 8
//    val bitOffset = 7 - (bitIndex % 8)
//    val b = bytes(byteIndex) & 0xFF
//    (b >> bitOffset) & 1
//  }
//
//  def extractBits(bytes: Array[Byte], bitIndex: Int, bitsToRead: Int): Int = {
//    var value = 0
//    for (i <- 0 until bitsToRead) {
//      val bit = extractBit(bytes, bitIndex + i)
//      value = (value << 1) | bit
//    }
//    value
//  }

  private def extractBits(bytes: Array[Byte], startBit: Int, bitsToRead: Int): Int = {
    if (bitsToRead <= 0) return 0

    val startByte = startBit / 8
    val startBitOffset = startBit % 8
    var result = 0
    var remainingBits = bitsToRead
    var currentByte = startByte

    // Handle first byte specially if we're not starting at a byte boundary
    if (startBitOffset > 0) {
      val availableBits = 8 - startBitOffset
      val bitsToTake = math.min(remainingBits, availableBits)
      val mask = (1 << bitsToTake) - 1
      result = (bytes(currentByte) >> (8 - startBitOffset - bitsToTake)) & mask
      remainingBits -= bitsToTake
      currentByte += 1
    }

    // Process full bytes
    while (remainingBits >= 8) {
      result = (result << 8) | (bytes(currentByte) & 0xFF)
      remainingBits -= 8
      currentByte += 1
    }

    // Handle remaining bits in the last byte
    if (remainingBits > 0)
      result = (result << remainingBits) | ((bytes(currentByte) & 0xFF) >>> (8 - remainingBits))

    result
  }
}

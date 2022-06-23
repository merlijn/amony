package nl.amony.lib.hash

object Base32 {

  //  val alphabet2 = "abcdefghijklmnopqrstuvwxyz234567".toCharArray
  // format: off

  val alphabet: Array[Char] = Array(
    'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p',
    'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z', '2', '3', '4', '5', '6', '7'
  )
  // format: on

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
  def encode(bytes: Array[Byte]): String = {

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
}

package nl.amony.lib

object Base32 {
  val alphabet: List[Char] = List('a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p', 'q',
    'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z', '2', '3', '4', '5', '6', '7')

  val byteN = 8
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

  def encodeToBase32(bytes: Array[Byte]): String = {

    val size          = Math.ceil((byteN * bytes.length) / baseN.toDouble).toInt
    val stringBuilder = new StringBuilder()

    (0 until size).foreach { n =>
      val index: Int  = (baseN * n) / byteN
      val offset: Int = (baseN * n) % byteN

      val t = {
        if (offset + baseN < byteN) {
          bitsAt(bytes(index), offset, offset + baseN)
        } else if (index < bytes.length - 1) {

          val n2 = offset - (byteN - baseN) + 1
          val c1 = bitsAt(bytes(index), offset, byteN)
          val c2 = bitsAt(bytes(index + 1), 0, n2)

          (c1 << (n2 - 1)) | c2
        } else {
          bitsAt(bytes(index), offset, byteN)
        }
      }

      stringBuilder.addOne(alphabet(t & 0xff))
    }

    stringBuilder.toString()
  }
}

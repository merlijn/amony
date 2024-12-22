package nl.amony.service.resources.util

object Base16 {
  private val alphabet: Array[Char] = "0123456789abcdef".toCharArray
  def encode(bytes: Array[Byte]): String = {
    val hexChars = new Array[Char](bytes.length * 2)
    for (j <- 0 until bytes.length) {
      val v = bytes(j) & 0xFF
      hexChars(j * 2)     = alphabet(v >>> 4)
      hexChars(j * 2 + 1) = alphabet(v & 0x0F)
    }
    new String(hexChars)
  }
}

package nl.amony.lib.hash

object Base16 {
  private val alphabet: Array[Char]      = "0123456789abcdef".toCharArray
  def encode(bytes: Array[Byte]): String = {
    val hexChars = new Array[Char](bytes.length * 2)
    for j <- 0 until bytes.length do {
      val v = bytes(j) & 0xff
      hexChars(j * 2)     = alphabet(v >>> 4)
      hexChars(j * 2 + 1) = alphabet(v & 0x0f)
    }
    new String(hexChars)
  }
}

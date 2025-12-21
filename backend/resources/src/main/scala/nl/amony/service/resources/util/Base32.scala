package nl.amony.service.resources.util

object Base32 {

  val alphabet = "abcdefghijklmnopqrstuvwxyz234567".toCharArray

  /**
   * This currently works as is. Code looks a bit clunky and maybe inefficient though.
   *
   * @param bytes
   * @return
   */
  def encode(bytes: Array[Byte]): String = {
    val totalBits = bytes.length * 8
    var bitIndex  = 0
    val sb        = new StringBuilder

    while bitIndex < totalBits do {
      val bitsToRead = math.min(5, totalBits - bitIndex)
      val bits       = extractBits(bytes, bitIndex, bitsToRead)
      val c          = alphabet(bits)
      sb.append(c)
      bitIndex += bitsToRead
    }
    sb.toString
  }

  private def extractBits(bytes: Array[Byte], startBit: Int, bitsToRead: Int): Int = {
    if bitsToRead <= 0 then return 0

    val startByte      = startBit / 8
    val startBitOffset = startBit % 8
    var result         = 0
    var remainingBits  = bitsToRead
    var currentByte    = startByte

    // Handle first byte specially if we're not starting at a byte boundary
    if startBitOffset > 0 then {
      val availableBits = 8 - startBitOffset
      val bitsToTake    = math.min(remainingBits, availableBits)
      val mask          = (1 << bitsToTake) - 1
      result = (bytes(currentByte) >> (8 - startBitOffset - bitsToTake)) & mask
      remainingBits -= bitsToTake
      currentByte += 1
    }

    // Process full bytes
    while remainingBits >= 8 do {
      result = (result << 8) | (bytes(currentByte) & 0xff)
      remainingBits -= 8
      currentByte += 1
    }

    // Handle remaining bits in the last byte
    if remainingBits > 0 then result = (result << remainingBits) | ((bytes(currentByte) & 0xff) >>> (8 - remainingBits))

    result
  }
}

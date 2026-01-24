package nl.amony.lib.hash

import java.security.MessageDigest
import java.nio.ByteBuffer

/**
 * Pseudo-random permutation using a Feistel network.
 * Provides a 1-to-1 mapping from [0, maxVal] to [0, maxVal].
 *
 * @param seed   The random seed for the permutation
 * @param maxVal The maximum value (inclusive) of the range
 * @param rounds Number of Feistel rounds (default 6, more = more secure)
 */
class FeistelPermutation(seed: Long, bits: Int, rounds: Int = 6) {

  require(bits > 0 && bits <= 62, "bits must be in range [1, 62]")
  require(rounds >= 4, "At least 4 rounds recommended")

  val maxVal: Long = (1L << bits) - 1
  private val halfBits = bits / 2
  private val leftBits = bits - halfBits
  private val rightMask = (1L << halfBits) - 1
  private val leftMask = (1L << leftBits) - 1

  def permute(n: Long): Long = {
    require(n >= 0 && n <= maxVal)
    feistelForward(n)
  }

  def inverse(n: Long): Long = {
    require(n >= 0 && n <= maxVal)
    feistelBackward(n)
  }

  private def roundFunction(value: Long, round: Int): Long = {
    // Simple hash combining seed, value, and round
    var h = seed ^ (value * 0x9E3779B97F4A7C15L) ^ (round * 0xBF58476D1CE4E5B9L)
    h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L
    h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL
    h ^ (h >>> 31)
  }

  private def feistelForward(input: Long): Long = {
    var left = input >> halfBits
    var right = input & rightMask

    for r <- 0 until rounds do {
      val newRight = (left ^ roundFunction(right, r)) & leftMask
      left = right
      right = newRight
    }
    (left << leftBits) | right
  }

  private def feistelBackward(input: Long): Long = {
    var left = input >> leftBits
    var right = input & leftMask

    for r <- (0 until rounds).reverse do {
      val newLeft = (right ^ roundFunction(left, r)) & rightMask
      right = left
      left = newLeft
    }
    (left << halfBits) | right
  }
}


object FeistelPermutation {
  def apply(seed: Long, nrOfBits: Int, rounds: Int = 6): FeistelPermutation = {
    new FeistelPermutation(seed, nrOfBits, rounds)
  }
}

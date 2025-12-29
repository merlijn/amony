package nl.amony.lib.hash

import scala.util.Random

/**
 * Adapted from: https://github.com/ai/nanoid/blob/main/index.js
 */
object NanoId:

  val defaultNumberGenerator = new scala.util.Random()

  val defaultAlphabet = "_-0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray.toVector

  val defaultSize = 21

  def generate(random: Random, alphabet: Vector[Char], size: Int): String = doGenerate(defaultNumberGenerator, alphabet, size).mkString("")

  protected def doGenerate(random: Random, alphabet: Vector[Char], size: Int): List[Char] = {

    // First, a bitmask is necessary to generate the ID. The bitmask makes bytes
    // values closer to the alphabet size. The bitmask calculates the closest
    // `2^31 - 1` number, which exceeds the alphabet size.
    // For example, the bitmask for the alphabet size 30 is 31 (00011111).
    val mask = (2 << (Math.log(alphabet.length - 1) / Math.log(2)).floor.round) - 1

    // Next, a step determines how many random bytes to generate.
    // The number of random bytes gets decided upon the ID size, mask,
    // alphabet size, and magic number 1.6 (using 1.6 peaks at performance
    // according to benchmarks).
    val step = (1.6 * mask * size / alphabet.length).ceil.round.toInt

    random.nextBytes(step).toList.map(_ & mask).flatMap(a => alphabet.lift(a).toList).take(size)
  }

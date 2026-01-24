package nl.amony.lib.hash

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class FeistelPermutationSpec extends AnyWordSpecLike with Matchers {

  "FeistelPermutation" should {

    "produce a bijective mapping for 12 bits" in {
      val bits = 12
      val maxVal = (1L << bits) - 1 // 4095
      val permutation = FeistelPermutation(seed = 42L, nrOfBits = bits)

      val results = (0L to maxVal).map(permutation.permute)

      // Check all results are within valid range
      results.foreach { r =>
        r should be >= 0L
        r should be <= maxVal
      }

      // Check each output appears exactly once (bijection)
      results.toSet.size shouldBe (maxVal + 1).toInt
    }

    "have inverse that reverses permute for 12 bits" in {
      val bits = 12
      val maxVal = (1L << bits) - 1
      val permutation = FeistelPermutation(seed = 42L, nrOfBits = bits)

      (0L to maxVal).foreach { n =>
        val permuted = permutation.permute(n)
        val reversed = permutation.inverse(permuted)
        reversed shouldBe n
      }
    }

    "produce different permutations for different seeds" in {
      val bits = 12
      val maxVal = (1L << bits) - 1
      val perm1 = FeistelPermutation(seed = 1L, nrOfBits = bits)
      val perm2 = FeistelPermutation(seed = 2L, nrOfBits = bits)

      val results1 = (0L to maxVal).map(perm1.permute)
      val results2 = (0L to maxVal).map(perm2.permute)

      results1 should not equal results2
    }

    "work correctly for odd number of bits" in {
      val bits = 11
      val maxVal = (1L << bits) - 1 // 2047
      val permutation = FeistelPermutation(seed = 123L, nrOfBits = bits)

      val results = (0L to maxVal).map(permutation.permute)

      results.toSet.size shouldBe (maxVal + 1).toInt

      // Verify inverse
      (0L to maxVal).foreach { n =>
        permutation.inverse(permutation.permute(n)) shouldBe n
      }
    }
  }
}

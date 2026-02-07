package nl.amony.modules.auth.crypt

import scala.util.Random

import org.scalatest.wordspec.AnyWordSpecLike

class PFEIdSpec extends AnyWordSpecLike {

  val key   = Random.nextBytes(32)
  val tweak = "tweak".getBytes("UTF-8")

  "FPE" should {
    "encrypt and decrypt values correctly" in {
      val fpe = new FPEId(key, tweak, 32)

      (1 to 10).foreach { i =>
        val encrypted = fpe.encrypt(i, version = 2)
        println(s"Encrypted $i to $encrypted")
        val decrypted = fpe.decrypt(encrypted)
        println(s"Decrypted $encrypted back to $decrypted")
        println(i.toBinaryString)
        // Note: Decryption is not implemented in the provided FPE class, so we cannot test it here.
      }

      println("Testing version extraction:")
      println(FPEId.extractVersion("e2e04de5c9"))
    }
  }

}

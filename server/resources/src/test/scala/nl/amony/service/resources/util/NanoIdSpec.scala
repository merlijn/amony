package nl.amony.service.resources.util

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.wordspec.AnyWordSpecLike
import scribe.Logging

class NanoIdSpec extends AnyWordSpecLike with Logging {
  
  "NanoId" should {
    "generate a random id" in {
      val rnd = new scala.util.Random()

      (0 to 10).foreach(_ => println(NanoId.generate(rnd, NanoId.defaultAlphabet, 21)))
    }
  }
}

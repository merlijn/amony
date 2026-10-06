package nl.amony.modules.auth

import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import pureconfig.ConfigSource

class ExtraHeadersSpec extends AnyWordSpecLike with Matchers {

  "ExtraHeaders" when {

    "parse" should {

      "treat blank input as no headers" in {
        ExtraHeaders.parse("") shouldBe Right(ExtraHeaders.empty)
        ExtraHeaders.parse("   ") shouldBe Right(ExtraHeaders.empty)
      }

      "parse a single pair" in {
        ExtraHeaders.parse("X-Zitadel-Instance-Host: localhost:8080") shouldBe
          Right(ExtraHeaders(Map("X-Zitadel-Instance-Host" -> "localhost:8080")))
      }

      "parse multiple pairs and trim whitespace" in {
        ExtraHeaders.parse(" A: 1 ,B:2, C: 3 ") shouldBe
          Right(ExtraHeaders(Map("A" -> "1", "B" -> "2", "C" -> "3")))
      }

      "keep everything after the first colon as the value" in {
        ExtraHeaders.parse("Authorization: Bearer a:b:c") shouldBe
          Right(ExtraHeaders(Map("Authorization" -> "Bearer a:b:c")))
      }

      "fail on an entry without a colon" in {
        ExtraHeaders.parse("X-Good: 1, broken") shouldBe Left("'broken' is not a 'Name: Value' pair")
      }
    }

    "ConfigReader" should {

      "read a comma-separated string" in {
        ConfigSource
          .fromConfig(ConfigFactory.parseString("headers = \"A: 1, B: 2\""))
          .at("headers")
          .load[ExtraHeaders] shouldBe Right(ExtraHeaders(Map("A" -> "1", "B" -> "2")))
      }
    }
  }
}

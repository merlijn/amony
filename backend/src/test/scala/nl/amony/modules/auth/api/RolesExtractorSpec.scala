package nl.amony.modules.auth.api

import io.circe.Json
import io.circe.parser.parse
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class RolesExtractorSpec extends AnyWordSpecLike with Matchers {

  private def json(input: String): Json = parse(input).getOrElse(fail("invalid test json"))

  "RolesExtractor" when {

    "extract" should {

      "read an array claim" in {
        RolesExtractor.extract(json("""{"roles":["admin","user"]}"""), "roles", RolesFrom.Array) shouldBe Set(Role("admin"), Role("user"))
      }

      "read a nested array claim through a dot-path" in {
        val userInfo = json("""{"realm_access":{"roles":["admin"]}}""")
        RolesExtractor.extract(userInfo, "realm_access.roles", RolesFrom.Array) shouldBe Set(Role("admin"))
      }

      "read an object claim as its keys" in {
        val userInfo = json("""{"urn:zitadel:iam:org:project:roles":{"admin":{"org":"x"},"user":{}}}""")
        RolesExtractor.extract(userInfo, "urn:zitadel:iam:org:project:roles", RolesFrom.ObjectKeys) shouldBe
          Set(Role("admin"), Role("user"))
      }

      "return no roles when the claim is missing" in {
        RolesExtractor.extract(json("""{"email":"a@b.c"}"""), "roles", RolesFrom.Array) shouldBe empty
      }

      "return no roles when the claim has an unexpected shape" in {
        RolesExtractor.extract(json("""{"roles":{"admin":{}}}"""), "roles", RolesFrom.Array) shouldBe empty
        RolesExtractor.extract(json("""{"roles":["admin"]}"""), "roles", RolesFrom.ObjectKeys) shouldBe empty
      }

      "ignore non-string array elements" in {
        RolesExtractor.extract(json("""{"roles":["admin",42,null]}"""), "roles", RolesFrom.Array) shouldBe Set(Role("admin"))
      }
    }
  }
}

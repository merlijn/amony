package nl.amony.modules.auth

import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import pureconfig.ConfigSource
import sttp.model.Uri

import nl.amony.modules.auth.api.{Role, RolesFrom}

class AuthConfigSpec extends AnyWordSpecLike with Matchers {

  private def loadDefaultRoles(raw: String): DefaultRoles =
    ConfigSource
      .fromConfig(ConfigFactory.parseString(s"default-roles = \"$raw\""))
      .at("default-roles")
      .load[DefaultRoles]
      .getOrElse(fail(s"could not load '$raw'"))

  private val provider = IdentityProvider(
    name         = "idp",
    clientId     = "client",
    clientSecret = "secret",
    authorizeUrl = Uri.unsafeParse("https://idp.example.com/authorize"),
    tokenUrl     = Uri.unsafeParse("https://idp.example.com/token"),
    userInfoUrl  = Uri.unsafeParse("https://idp.example.com/userinfo")
  )

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

  "DefaultRoles" when {

    "empty" should {

      "be empty by default" in {
        DefaultRoles.empty.values shouldBe empty
      }
    }

    "ConfigReader" should {

      "read an empty string as no roles" in {
        loadDefaultRoles("").values shouldBe empty
      }

      "read a comma-separated list" in {
        loadDefaultRoles("admin").values shouldBe Set(Role.Admin)
        loadDefaultRoles("admin,user").values shouldBe Set(Role("admin"), Role("user"))
      }

      "trim whitespace and ignore blank entries" in {
        loadDefaultRoles(" admin , user , , moderator ").values shouldBe Set(Role("admin"), Role("user"), Role("moderator"))
      }
    }
  }

  "IdentityProvider" when {

    "rolesClaim" should {

      "default to none" in {
        provider.rolesClaim shouldBe None
      }

      "keep a configured claim" in {
        val zitadel = provider.copy(rolesClaim = Some("urn:zitadel:iam:org:project:roles"))
        zitadel.rolesClaim shouldBe Some("urn:zitadel:iam:org:project:roles")
      }
    }

    "rolesFrom" should {

      "default to the array shape" in {
        provider.rolesFrom shouldBe RolesFrom.Array
      }

      "keep a configured shape" in {
        val zitadel = provider.copy(rolesFrom = RolesFrom.ObjectKeys)
        zitadel.rolesFrom shouldBe RolesFrom.ObjectKeys
      }
    }
  }
}

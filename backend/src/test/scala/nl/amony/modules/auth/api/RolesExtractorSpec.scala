package nl.amony.modules.auth.api

import io.circe.Json
import io.circe.parser.parse
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import sttp.model.Uri

import nl.amony.modules.auth.{IdentityProvider, ProviderType}

class RolesExtractorSpec extends AnyWordSpecLike with Matchers {

  private def json(input: String): Json = parse(input).getOrElse(fail("invalid test json"))

  private def provider(
    providerType: ProviderType           = ProviderType.Generic,
    rolesClaim: Option[String]           = None,
    rolesFrom: Option[RolesFrom]         = None
  ): IdentityProvider = IdentityProvider(
    name         = "idp",
    clientId     = "client",
    clientSecret = "secret",
    authorizeUrl = Uri.unsafeParse("https://idp.example.com/authorize"),
    tokenUrl     = Uri.unsafeParse("https://idp.example.com/token"),
    userInfoUrl  = Uri.unsafeParse("https://idp.example.com/userinfo"),
    providerType = providerType,
    rolesClaim   = rolesClaim,
    rolesFrom    = rolesFrom
  )

  "RolesExtractor" should {

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

  "IdentityProvider" should {

    "default to no roles claim for a generic provider" in {
      provider().effectiveRolesClaim shouldBe None
      provider().effectiveRolesFrom shouldBe RolesFrom.Array
    }

    "use the provider type preset for the roles claim" in {
      val zitadel = provider(providerType = ProviderType.Zitadel)
      zitadel.effectiveRolesClaim shouldBe Some("urn:zitadel:iam:org:project:roles")
      zitadel.effectiveRolesFrom shouldBe RolesFrom.ObjectKeys
    }

    "let explicit config override the provider type preset" in {
      val overridden = provider(providerType = ProviderType.Zitadel, rolesClaim = Some("groups"), rolesFrom = Some(RolesFrom.Array))
      overridden.effectiveRolesClaim shouldBe Some("groups")
      overridden.effectiveRolesFrom shouldBe RolesFrom.Array
    }
  }
}

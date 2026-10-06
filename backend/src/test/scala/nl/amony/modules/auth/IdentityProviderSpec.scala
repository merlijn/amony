package nl.amony.modules.auth

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import sttp.model.Uri

import nl.amony.modules.auth.api.RolesFrom

class IdentityProviderSpec extends AnyWordSpecLike with Matchers {

  private val provider = IdentityProvider(
    name         = "idp",
    clientId     = "client",
    clientSecret = "secret",
    authorizeUrl = Uri.unsafeParse("https://idp.example.com/authorize"),
    tokenUrl     = Uri.unsafeParse("https://idp.example.com/token"),
    userInfoUrl  = Uri.unsafeParse("https://idp.example.com/userinfo")
  )

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

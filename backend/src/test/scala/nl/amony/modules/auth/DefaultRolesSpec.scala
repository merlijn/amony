package nl.amony.modules.auth

import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import pureconfig.ConfigSource

import nl.amony.modules.auth.api.Role

class DefaultRolesSpec extends AnyWordSpecLike with Matchers {

  private def loadDefaultRoles(raw: String): DefaultRoles =
    ConfigSource
      .fromConfig(ConfigFactory.parseString(s"default-roles = \"$raw\""))
      .at("default-roles")
      .load[DefaultRoles]
      .getOrElse(fail(s"could not load '$raw'"))

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
}

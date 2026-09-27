package nl.amony.modules.auth.api

import org.scalatest.wordspec.AnyWordSpecLike

class ApiSecuritySpec extends AnyWordSpecLike {

  "ApiSecurity.isAllowedHost" should {
    "accept a listed host" in {
      assert(ApiSecurity.isAllowedHost(List("localhost:8182"), "localhost:8182"))
    }
    "match case-insensitively" in {
      assert(ApiSecurity.isAllowedHost(List("Demo.Amony.App"), "demo.amony.app"))
    }
    "reject an unlisted host" in {
      assert(!ApiSecurity.isAllowedHost(List("demo.amony.app"), "evil.example.com"))
    }
    "reject an empty host" in {
      assert(!ApiSecurity.isAllowedHost(List("demo.amony.app"), ""))
    }
  }
}

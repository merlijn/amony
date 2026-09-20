package nl.amony.lib.http

import org.scalatest.wordspec.AnyWordSpecLike

class HostCheckSpec extends AnyWordSpecLike {

  "HostCheck.effectiveHost" should {
    "prefer X-Forwarded-Host over Host" in {
      assert(HostCheck.effectiveHost(Some("admin.example.com"), Some("backend:8182")) == "admin.example.com")
    }
    "fall back to Host when X-Forwarded-Host is absent" in {
      assert(HostCheck.effectiveHost(None, Some("localhost:8182")) == "localhost:8182")
    }
    "take the first value of a comma-separated list" in {
      assert(HostCheck.effectiveHost(Some("a.example.com, b.example.com"), None) == "a.example.com")
    }
    "be empty when neither header is present" in {
      assert(HostCheck.effectiveHost(None, None) == "")
    }
  }

  "HostCheck.isAllowed" should {
    "accept a listed host" in {
      assert(HostCheck.isAllowed(List("localhost:8182"), "localhost:8182"))
    }
    "match case-insensitively" in {
      assert(HostCheck.isAllowed(List("Demo.Amony.App"), "demo.amony.app"))
    }
    "reject an unlisted host" in {
      assert(!HostCheck.isAllowed(List("demo.amony.app"), "evil.example.com"))
    }
    "reject an empty host" in {
      assert(!HostCheck.isAllowed(List("demo.amony.app"), ""))
    }
  }
}

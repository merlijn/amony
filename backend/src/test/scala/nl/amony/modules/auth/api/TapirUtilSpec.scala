package nl.amony.modules.auth.api

import org.scalatest.wordspec.AnyWordSpecLike

class TapirUtilSpec extends AnyWordSpecLike {

  "effectiveHost" should {
    "prefer X-Forwarded-Host over Host" in {
      assert(effectiveHost(Some("admin.example.com"), Some("backend:8182")) == "admin.example.com")
    }
    "fall back to Host when X-Forwarded-Host is absent" in {
      assert(effectiveHost(None, Some("localhost:8182")) == "localhost:8182")
    }
    "take the first value of a comma-separated list" in {
      assert(effectiveHost(Some("a.example.com, b.example.com"), None) == "a.example.com")
    }
    "be empty when neither header is present" in {
      assert(effectiveHost(None, None) == "")
    }
  }
}

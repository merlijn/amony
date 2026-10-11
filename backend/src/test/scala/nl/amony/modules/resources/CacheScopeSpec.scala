package nl.amony.modules.resources

import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike
import pureconfig.ConfigSource

import nl.amony.modules.resources.api.CacheScope

class CacheScopeSpec extends AnyWordSpecLike {

  private def read(value: String) =
    ConfigSource.fromConfig(ConfigFactory.parseString(s"""scope = "$value"""")).at("scope").load[CacheScope]

  "CacheScope" should {

    "read the supported scopes" in {
      assert(read("public") == Right(CacheScope.Public))
      assert(read("private") == Right(CacheScope.Private))
    }

    "reject an unknown scope" in
      assert(read("shared").isLeft)
  }
}

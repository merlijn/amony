package nl.amony.modules.resources

import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike
import pureconfig.ConfigSource

class ResolutionPickingStrategySpec extends AnyWordSpecLike {

  private def read(value: String) =
    ConfigSource.fromConfig(ConfigFactory.parseString(s"""strategy = "$value"""")).at("strategy").load[ResolutionPickingStrategy]

  "ResolutionPickingStrategy" should {

    "read the supported strategies" in {
      assert(read("round-up") == Right(ResolutionPickingStrategy.RoundUp))
      assert(read("round-down") == Right(ResolutionPickingStrategy.RoundDown))
      assert(read("round-nearest") == Right(ResolutionPickingStrategy.RoundNearest))
    }

    "reject an unknown strategy" in
      assert(read("sideways").isLeft)
  }
}

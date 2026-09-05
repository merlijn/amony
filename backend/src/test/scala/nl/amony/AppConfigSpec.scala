package nl.amony

import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike
import pureconfig.ConfigSource

class AppConfigSpec extends AnyWordSpecLike {

  "AppConfig" should {
    "load config" in {
      val appConfig: AppConfig = ConfigSource.fromConfig(ConfigFactory.load()).at("amony").loadOrThrow[AppConfig]
      println(s"enabled: ${appConfig.auth.enabled}")
      println(s"require-login: ${appConfig.auth.publicUri}")
    }
  }
}

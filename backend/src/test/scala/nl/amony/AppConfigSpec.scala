package nl.amony

import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike
import pureconfig.ConfigSource

class AppConfigSpec extends AnyWordSpecLike {

  "AppConfig" should {
    "successfully load config" in {
      val appConfig: AppConfig = ConfigSource.fromConfig(ConfigFactory.load()).at("amony").loadOrThrow[AppConfig]
      println(s"enabled: ${appConfig.auth.enabled}")
      println(s"require-login: ${appConfig.auth.requireLogin}")
      println(s"allowed-hosts: ${appConfig.api.allowedHosts}")
      println(s"allowed-resolutions: ${appConfig.resources.thumbnails.allowedResolutions}")
      println(s"supported-formats: ${appConfig.resources.thumbnails.supportedFormats}")
      println(s"resolution-picking-strategy: ${appConfig.resources.thumbnails.resolutionPickingStrategy}")
      assert(appConfig.api.allowedHosts.nonEmpty)
      assert(appConfig.resources.thumbnails.allowedResolutions.nonEmpty)
      assert(appConfig.resources.thumbnails.allowedResolutions.contains(appConfig.resources.thumbnails.defaultResolution))
      assert(appConfig.resources.thumbnails.supportedFormats.nonEmpty)
    }
  }
}

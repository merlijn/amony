package nl.amony

import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike
import pureconfig.ConfigSource

import nl.amony.modules.resources.api.ImageFormat

class AppConfigSpec extends AnyWordSpecLike {

  "AppConfig" should {
    "successfully load config" in {
      val appConfig: AppConfig = ConfigSource.fromConfig(ConfigFactory.load()).at("amony").loadOrThrow[AppConfig]
      println(s"enabled: ${appConfig.auth.enabled}")
      println(s"require-login: ${appConfig.auth.requireLogin}")
      println(s"allowed-hosts: ${appConfig.api.allowedHosts}")
      println(s"allowed-resolutions: ${appConfig.resources.previews.allowedResolutions}")
      println(s"supported-formats: ${appConfig.resources.previews.supportedFormats}")
      println(s"resolution-picking-strategy: ${appConfig.resources.previews.resolutionPickingStrategy}")
      println(s"format-options: ${appConfig.resources.previews.formatOptions}")
      assert(appConfig.api.allowedHosts.nonEmpty)
      assert(appConfig.resources.previews.allowedResolutions.nonEmpty)
      assert(appConfig.resources.previews.allowedResolutions.contains(appConfig.resources.previews.defaultResolution))
      assert(appConfig.resources.previews.supportedFormats.nonEmpty)
      // AVIF is dropped by default: the bundled SVT-AV1 encoder cannot encode sides below 64px.
      assert(!appConfig.resources.previews.supportedFormats.contains(ImageFormat.Avif))
      // Encoder arguments are configured per format rather than hardcoded in ImageFormat.
      assert(appConfig.resources.previews.formatOptions.get(ImageFormat.Avif).exists(_.contains("libsvtav1")))
    }
  }
}

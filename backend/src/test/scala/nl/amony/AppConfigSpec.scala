package nl.amony

import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike
import pureconfig.ConfigSource
import scribe.Logging

import nl.amony.modules.resources.api.ImageFormat

class AppConfigSpec extends AnyWordSpecLike with Logging {

  "AppConfig" should {
    "successfully load config" in {
      val appConfig: AppConfig = ConfigSource.fromConfig(ConfigFactory.load()).at("amony").loadOrThrow[AppConfig]
      logger.info(s"Parsed config: $appConfig")
      assert(appConfig.api.allowedHosts.nonEmpty)
      assert(appConfig.resources.previews.allowedResolutions.nonEmpty)
      assert(appConfig.resources.previews.allowedResolutions.contains(appConfig.resources.previews.defaultResolution))
      assert(appConfig.resources.previews.supportedImageFormats.nonEmpty)
      assert(appConfig.resources.previews.supportedVideoFormats == List("mp4"))
      // AVIF is dropped by default: the bundled SVT-AV1 encoder cannot encode sides below 64px.
      assert(!appConfig.resources.previews.supportedImageFormats.contains(ImageFormat.Avif))
      // Encoder arguments are configured per format rather than hardcoded in ImageFormat.
      assert(appConfig.resources.previews.formatOptions.get(ImageFormat.Avif).exists(_.contains("libsvtav1")))
    }
  }
}

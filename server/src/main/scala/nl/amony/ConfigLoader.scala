package nl.amony

import com.typesafe.config.ConfigFactory
import nl.amony.actor.media.MediaConfig.DeleteMediaOption
import nl.amony.actor.media.MediaConfig.HashingAlgorithm
import nl.amony.actor.media.MediaConfig.MediaLibConfig
import nl.amony.http.WebServerConfig
import nl.amony.user.AuthConfig
import scribe.Logging

case class AmonyConfig(
    media: MediaLibConfig,
    api: WebServerConfig,
    auth: AuthConfig
)

trait ConfigLoader extends Logging {

  import pureconfig._
  import pureconfig.generic.auto._
  import pureconfig.module.squants._
  import pureconfig.generic.semiauto.deriveEnumerationReader

  implicit val hashingAlgorithmReader: ConfigReader[HashingAlgorithm] = deriveEnumerationReader[HashingAlgorithm]
  implicit val deleteMediaOption: ConfigReader[DeleteMediaOption]     = deriveEnumerationReader[DeleteMediaOption]

  val config       = ConfigFactory.load()
  val configSource = ConfigSource.fromConfig(config)

  val appConfig = configSource.at("amony").loadOrThrow[AmonyConfig]
}

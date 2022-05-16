package nl.amony

import com.typesafe.config.ConfigFactory
import nl.amony.actor.media.MediaConfig.DeleteMediaOption
import nl.amony.actor.media.MediaConfig.HashingAlgorithm
import nl.amony.actor.media.MediaConfig.MediaLibConfig
import nl.amony.user.AuthConfig
import scribe.Logging
import squants.information.Information

import scala.concurrent.duration.FiniteDuration

case class AmonyConfig(
    media: MediaLibConfig,
    api: WebServerConfig,
    auth: AuthConfig
)

case class WebServerConfig(
    hostName: String,
    webClientPath: String,
    requestTimeout: FiniteDuration,
    enableAdmin: Boolean,
    uploadSizeLimit: Information,
    defaultNumberOfResults: Int,
    http: Option[HttpConfig],
    https: Option[HttpsConfig]
)

case class HttpsConfig(
    enabled: Boolean,
    port: Int,
    privateKeyPem: String,
    certificateChainPem: String
)

case class HttpConfig(
    enabled: Boolean,
    port: Int
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

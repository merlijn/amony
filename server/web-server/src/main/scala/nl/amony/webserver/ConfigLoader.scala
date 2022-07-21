package nl.amony.webserver

import com.typesafe.config.ConfigFactory
import nl.amony.search.SearchConfig
import nl.amony.service.auth.AuthConfig
import nl.amony.service.media.MediaConfig.{DeleteMediaOption, HashingAlgorithm, LocalResourcesConfig}
import scribe.Logging

case class AmonyConfig(
  media: LocalResourcesConfig,
  api: WebServerConfig,
  search: SearchConfig
)

trait ConfigLoader extends Logging {

  import pureconfig._
  import pureconfig.generic.auto._
  import pureconfig.module.squants._

  val config       = ConfigFactory.load()
  val configSource = ConfigSource.fromConfig(config)

  val appConfig = configSource.at("amony").loadOrThrow[AmonyConfig]
}

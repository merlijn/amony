package nl.amony.webserver

import com.typesafe.config.ConfigFactory
import nl.amony.search.SearchConfig
import nl.amony.service.resources.ResourceConfig.*
import pureconfig._
import pureconfig.generic.derivation.default._
import scribe.Logging

import java.nio.file.Path

case class AmonyConfig(
  amonyPath: Path,
  resources: List[ResourceBucketConfig],
  api: WebServerConfig,
  search: SearchConfig
) derives ConfigReader

trait ConfigLoader extends Logging {

  import pureconfig._

  val config       = ConfigFactory.load()
  val configSource = ConfigSource.fromConfig(config)

  val appConfig = configSource.at("amony").loadOrThrow[AmonyConfig]
}

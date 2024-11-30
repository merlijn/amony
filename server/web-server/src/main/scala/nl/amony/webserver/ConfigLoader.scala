package nl.amony.webserver

import com.typesafe.config.ConfigFactory
import nl.amony.search.SearchConfig
import nl.amony.search.solr.SolrConfig
import nl.amony.service.resources.ResourceConfig.*
import pureconfig.*
import pureconfig.generic.derivation.default.*
import scribe.Logging

import java.nio.file.Path
import scala.reflect.ClassTag

case class AmonyConfig(
    amonyHome: Path,
    resources: List[ResourceBucketConfig],
    api: WebServerConfig,
    search: SearchConfig,
    solr: SolrConfig
) derives ConfigReader

trait ConfigLoader extends Logging {

  import pureconfig._

  val config       = ConfigFactory.load()
  val configSource = ConfigSource.fromConfig(config)
  val appConfig    = configSource.at("amony").loadOrThrow[AmonyConfig]

  def loadConfig[T: ClassTag : ConfigReader](path: String): T = {

    val configSource = ConfigSource.fromConfig(config.getConfig(path))
    val configObj = configSource.loadOrThrow[T]

    configObj
  }
}

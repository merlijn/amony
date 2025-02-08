package nl.amony.app

import com.typesafe.config.ConfigFactory
import nl.amony.search.SearchConfig
import nl.amony.search.solr.SolrConfig
import nl.amony.service.resources.ResourceConfig.*
import nl.amony.service.resources.database.DatabaseConfig
import pureconfig.*
import scribe.Logging

import java.nio.file.Path
import scala.reflect.ClassTag

case class AmonyConfig(
    amonyHome: Path,
    resources: List[ResourceBucketConfig],
    api: WebServerConfig,
    search: SearchConfig,
    solr: SolrConfig,
    db: DatabaseConfig,
) derives ConfigReader

trait ConfigLoader extends Logging {

  lazy val config       = {
    Option(System.getenv().get("AMONY_CONFIG_FILE")) match
      case Some(fileName) =>
        logger.info(s"Loading configuration from file: $fileName")
        ConfigFactory.parseFile(Path.of(fileName).toFile)
      case None =>
        ConfigFactory.load()
  }

  lazy val appConfig    = {
    val configSource = ConfigSource.fromConfig(config)
    configSource.at("amony").loadOrThrow[AmonyConfig]
  }

  def loadConfig[T: ClassTag : ConfigReader](path: String): T = {

    val configSource = ConfigSource.fromConfig(config.getConfig(path))
    val configObj = configSource.loadOrThrow[T]

    configObj
  }
}

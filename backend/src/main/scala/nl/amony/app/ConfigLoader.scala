package nl.amony.app

import java.nio.file.Path

import scala.reflect.ClassTag

import com.typesafe.config.{Config, ConfigFactory}
import pureconfig.*
import scribe.Logging

import nl.amony.modules.resources.ResourceConfig.ResourceBucketConfig
import nl.amony.modules.resources.database.DatabaseConfig
import nl.amony.modules.search.SearchConfig
import nl.amony.modules.search.solr.SolrConfig

case class AmonyConfig(
  amonyHome: Path,
  resources: List[ResourceBucketConfig],
  api: WebServerConfig,
  search: SearchConfig,
  solr: SolrConfig,
  database: DatabaseConfig
) derives ConfigReader

trait ConfigLoader extends Logging {

  lazy val config: Config =
    Option(System.getenv().get("AMONY_CONFIG_FILE")) match
      case Some(fileName) =>
        logger.info(s"Loading configuration from file: $fileName")
        ConfigFactory.parseFile(Path.of(fileName).toFile)
      case None           => ConfigFactory.load()

  lazy val appConfig: AmonyConfig = {
    val configSource = ConfigSource.fromConfig(config)
    configSource.at("amony").loadOrThrow[AmonyConfig]
  }

  def loadConfig[T: {ClassTag, ConfigReader}](path: String): T = {

    val configSource = ConfigSource.fromConfig(config.getConfig(path))
    val configObj    = configSource.loadOrThrow[T]

    configObj
  }
}

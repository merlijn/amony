package nl.amony.app

import java.nio.file.Path

import pureconfig.*

import nl.amony.modules.resources.ResourceConfig.ResourceBucketConfig
import nl.amony.modules.resources.database.DatabaseConfig
import nl.amony.modules.search.SearchConfig
import nl.amony.modules.search.solr.SolrConfig

case class AppConfig(
  amonyHome: Path,
  resources: List[ResourceBucketConfig],
  api: WebServerConfig,
  search: SearchConfig,
  solr: SolrConfig,
  database: DatabaseConfig
) derives ConfigReader

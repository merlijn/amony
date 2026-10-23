package nl.amony

import pureconfig.*

import nl.amony.lib.database.DatabaseConfig
import nl.amony.lib.messagebus.MessageBusConfig
import nl.amony.lib.observability.ObservabilityConfig
import nl.amony.modules.auth.AuthConfig
import nl.amony.modules.resources.ResourceConfig
import nl.amony.modules.search.SearchConfig
import nl.amony.modules.search.solr.SolrConfig

case class AppConfig(
  resources: ResourceConfig,
  auth: AuthConfig,
  api: WebServerConfig,
  search: SearchConfig,
  database: DatabaseConfig,
  messageBus: MessageBusConfig,
  observability: ObservabilityConfig
) derives ConfigReader

package nl.amony

import pureconfig.*

import nl.amony.modules.auth.AuthConfig
import nl.amony.modules.resources.ResourceConfig
import nl.amony.modules.search.SearchConfig
import nl.amony.platform.database.DatabaseConfig
import nl.amony.platform.messagebus.MessageBusConfig
import nl.amony.platform.observability.ObservabilityConfig

case class AppConfig(
  resources: ResourceConfig,
  auth: AuthConfig,
  api: WebServerConfig,
  search: SearchConfig,
  database: DatabaseConfig,
  messageBus: MessageBusConfig,
  observability: ObservabilityConfig
) derives ConfigReader

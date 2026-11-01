package nl.amony.app

import pureconfig.*

import nl.amony.app.observability.ObservabilityConfig
import nl.amony.modules.auth.AuthConfig
import nl.amony.modules.resources.ResourceConfig
import nl.amony.modules.search.SearchConfig
import nl.amony.platform.database.DatabaseConfig
import nl.amony.platform.messagebus.MessageBusConfig
import nl.amony.platform.subprocess.ProcessSettings

given ConfigReader[ProcessSettings] = ConfigReader.derived

case class AppConfig(
  resources: ResourceConfig,
  auth: AuthConfig,
  api: WebServerConfig,
  search: SearchConfig,
  database: DatabaseConfig,
  messageBus: MessageBusConfig,
  mediaProcessing: ProcessSettings,
  observability: ObservabilityConfig
) derives ConfigReader

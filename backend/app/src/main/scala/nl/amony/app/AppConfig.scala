package nl.amony.app

import pureconfig.*
import pureconfig.error.CannotConvert

import nl.amony.app.observability.ObservabilityConfig
import nl.amony.modules.auth.AuthConfig
import nl.amony.modules.resources.ResourceConfig
import nl.amony.modules.search.SearchConfig
import nl.amony.platform.database.DatabaseConfig
import nl.amony.platform.messagebus.MessageBusConfig
import nl.amony.platform.subprocess.{HardwareDecode, ProcessSettings}

given ConfigReader[HardwareDecode] =
  ConfigReader[String].emap { name =>
    HardwareDecode.values.find(_.configName == name).toRight(
      CannotConvert(name, "HardwareDecode", s"Expected one of: ${HardwareDecode.values.map(_.configName).mkString(", ")}")
    )
  }

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

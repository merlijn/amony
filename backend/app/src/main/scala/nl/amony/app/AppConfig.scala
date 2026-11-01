package nl.amony.app

import pureconfig.*
import pureconfig.error.CannotConvert

import nl.amony.app.observability.ObservabilityConfig
import nl.amony.modules.auth.AuthConfig
import nl.amony.modules.resources.ResourceConfig
import nl.amony.modules.search.SearchConfig
import nl.amony.platform.database.DatabaseConfig
import nl.amony.platform.messagebus.MessageBusConfig
import nl.amony.platform.subprocess.{HardwareDecodeConfig, HardwareDecodeMode, ProcessSettings}

given ConfigReader[HardwareDecodeMode] =
  ConfigReader[String].emap { name =>
    HardwareDecodeMode.fromConfigName(name).toRight(
      CannotConvert(name, "HardwareDecodeMode", s"Expected one of: ${HardwareDecodeMode.values.map(_.configName).mkString(", ")}")
    )
  }

given ConfigReader[HardwareDecodeConfig] = ConfigReader.derived

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

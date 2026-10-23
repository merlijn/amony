package nl.amony.lib.observability

import pureconfig.ConfigReader

case class ObservabilityConfig(
  otelEnabled: Boolean,
  logLevels: Map[String, String]
) derives ConfigReader

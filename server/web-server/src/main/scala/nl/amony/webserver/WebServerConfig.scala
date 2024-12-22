package nl.amony.webserver

import scala.concurrent.duration.FiniteDuration
import pureconfig._
import pureconfig.generic.derivation.default._

case class WebServerConfig(
  webClientPath: String,
  requestTimeout: FiniteDuration,
  uploadSizeLimit: Long,
  http: Option[HttpConfig],
  https: Option[HttpsConfig]
) derives ConfigReader

case class HttpsConfig(
  host: String,
  port: Int,
  enabled: Boolean,
  privateKeyPem: String,
  certificateChainPem: String
) derives ConfigReader

case class HttpConfig(
  host: String,
  port: Int,
  enabled: Boolean,
) derives ConfigReader

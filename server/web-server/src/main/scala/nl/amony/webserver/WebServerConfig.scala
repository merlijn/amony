package nl.amony.webserver

import scala.concurrent.duration.FiniteDuration
import pureconfig._
import pureconfig.generic.derivation.default._

case class WebServerConfig(
  hostName: String,
  webClientPath: String,
  requestTimeout: FiniteDuration,
  uploadSizeLimit: Long,
  http: Option[HttpConfig],
  https: Option[HttpsConfig]
) derives ConfigReader

case class HttpsConfig(
  enabled: Boolean,
  port: Int,
  privateKeyPem: String,
  certificateChainPem: String
) derives ConfigReader

case class HttpConfig(
  enabled: Boolean,
  port: Int
) derives ConfigReader

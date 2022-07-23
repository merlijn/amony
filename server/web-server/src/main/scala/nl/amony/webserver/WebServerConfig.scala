package nl.amony.webserver

import squants.information.Information

import scala.concurrent.duration.FiniteDuration

case class WebServerConfig(
  hostName: String,
  webClientPath: String,
  requestTimeout: FiniteDuration,
  uploadSizeLimit: Information,
  http: Option[HttpConfig],
  https: Option[HttpsConfig]
)

case class HttpsConfig(
  enabled: Boolean,
  port: Int,
  privateKeyPem: String,
  certificateChainPem: String
)

case class HttpConfig(
 enabled: Boolean,
 port: Int
)

package nl.amony.http

import scala.concurrent.duration.FiniteDuration

case class WebServerConfig(
    hostName: String,
    webClientPath: String,
    requestTimeout: FiniteDuration,
    enableAdmin: Boolean,
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

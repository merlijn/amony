package io.amony.http

import scala.concurrent.duration.FiniteDuration

case class WebServerConfig(
    hostName: String,
    webClientFiles: String,
    requestTimeout: FiniteDuration,
    http: Option[HttpConfig],
    https: Option[HttpsConfig]
)

case class HttpsConfig(
    port: Int,
    privateKeyPem: String,
    certificateChainPem: String
)

case class HttpConfig(
    port: Int
)

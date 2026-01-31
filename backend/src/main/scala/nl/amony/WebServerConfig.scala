package nl.amony

import java.nio.file.Path
import scala.concurrent.duration.FiniteDuration

import pureconfig.*

case class WebServerConfig(
  webClientPath: Path,
  requestTimeout: FiniteDuration,
  uploadSizeLimit: Long,
  http: Option[HttpConfig],
  https: Option[HttpsConfig]
) derives ConfigReader

case class HttpsConfig(host: String, port: Int, enabled: Boolean, privateKeyPem: String, certificateChainPem: String) derives ConfigReader

case class HttpConfig(host: String, port: Int, enabled: Boolean) derives ConfigReader

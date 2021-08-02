package io.amony

import com.typesafe.config.ConfigFactory
import io.amony.http.WebServerConfig
import pureconfig._
import pureconfig.generic.auto._
import scribe.Logging

import java.nio.file.Path

case class MediaLibConfig(
    libraryPath: Path,
    indexPath: Path,
    scanParallelFactor: Int,
    verifyHashes: Boolean,
    max: Option[Int]
)

trait AppConfig extends Logging {

  val env = {
    if (System.getenv().containsKey("ENV"))
      System.getenv().get("ENV")
    else
      "dev"
  }

  val config       = ConfigFactory.load(s"$env/application.conf")
  val configSource = ConfigSource.fromConfig(config)

  val mediaLibConfig  = configSource.at("media").loadOrThrow[MediaLibConfig]
  val webServerConfig = configSource.at("http").loadOrThrow[WebServerConfig]
}

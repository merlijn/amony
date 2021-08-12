package io.amony

import better.files.File
import com.typesafe.config.ConfigFactory
import io.amony.http.WebServerConfig
import io.amony.lib.FileUtil
import pureconfig._
import pureconfig.generic.auto._
import pureconfig.generic.semiauto.deriveEnumerationReader
import scribe.Logging

import java.nio.file.Path

case class MediaLibConfig(
    libraryPath: Path,
    indexPath: Path,
    scanParallelFactor: Int,
    verifyHashes: Boolean,
    hashingAlgorithm: HashingAlgorithm,
    max: Option[Int]
)

sealed trait HashingAlgorithm {
  def generateHash(path: Path): String
}

case object FakeHash extends HashingAlgorithm {
  override def generateHash(path: Path): String = FileUtil.fakeHash(File(path))
}

trait AppConfig extends Logging {

  val env = {
    if (System.getenv().containsKey("ENV"))
      System.getenv().get("ENV")
    else
      "dev"
  }

  implicit val hashingAlgorithmReader: ConfigReader[HashingAlgorithm] = deriveEnumerationReader[HashingAlgorithm]

  val config       = ConfigFactory.load(s"$env/application.conf")
  val configSource = ConfigSource.fromConfig(config)

  val mediaLibConfig  = configSource.at("media").loadOrThrow[MediaLibConfig]
  val webServerConfig = configSource.at("http").loadOrThrow[WebServerConfig]
}

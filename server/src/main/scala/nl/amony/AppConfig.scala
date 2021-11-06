package nl.amony

import better.files.File
import com.typesafe.config.ConfigFactory
import nl.amony.http.WebServerConfig
import nl.amony.lib.FileUtil
import pureconfig._
import pureconfig.generic.auto._
import pureconfig.generic.semiauto.deriveEnumerationReader
import scribe.Logger
import scribe.Logging

import java.nio.file.Path
import scala.concurrent.duration.FiniteDuration

case class MediaLibConfig(
    path: Path,
    indexPath: Path,
    scanParallelFactor: Int,
    verifyExistingHashes: Boolean,
    hashingAlgorithm: HashingAlgorithm,
    previews: PreviewConfig
) {

  lazy val mediaPath = path.toAbsolutePath.normalize()
}

case class PreviewConfig(
    transcode: List[TranscodeSettings],
    minimumFragmentDuration: Option[FiniteDuration],
    maximumFragmentDuration: Option[FiniteDuration]
)

case class TranscodeSettings(
    format: String,
    scaleHeight: Int,
    crf: Int
)

sealed trait HashingAlgorithm {
  def generateHash(path: Path): String
}

case object PartialHash extends HashingAlgorithm {
  override def generateHash(path: Path): String = FileUtil.partialSha1Base32Hash(File(path), 512)
}

trait AppConfig extends Logging {

  implicit val hashingAlgorithmReader: ConfigReader[HashingAlgorithm] = deriveEnumerationReader[HashingAlgorithm]

  val config       = ConfigFactory.load()
  val configSource = ConfigSource.fromConfig(config)

  val mediaLibConfig  = configSource.at("amony.media").loadOrThrow[MediaLibConfig]
  val webServerConfig = configSource.at("amony.api").loadOrThrow[WebServerConfig]
}

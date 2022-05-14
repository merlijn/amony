package nl.amony

import com.typesafe.config.ConfigFactory
import squants.information.Information
import nl.amony.lib.hash.Base32
import nl.amony.lib.hash.PartialHash.partialHash
import nl.amony.user.AuthConfig
import scribe.Logging

import java.nio.file.Path
import java.security.MessageDigest
import scala.concurrent.duration.FiniteDuration

case class AmonyConfig(
  media: MediaLibConfig,
  api: WebServerConfig,
  users: UserConfig,
  ffprobeTimeout: FiniteDuration,
)

case class MediaLibConfig(
  path: Path,
  indexPath: Path,
  deleteMedia: DeleteMediaOption,
  relativeUploadPath: Path,
  scanParallelFactor: Int,
  verifyExistingHashes: Boolean,
  hashingAlgorithm: HashingAlgorithm,
  defaultFragmentLength: FiniteDuration,
  minimumFragmentLength: FiniteDuration,
  maximumFragmentLength: FiniteDuration,
  previews: PreviewConfig
) {

  lazy val resourcePath: Path = indexPath.resolve("resources")
  lazy val mediaPath: Path = path.toAbsolutePath.normalize()
  lazy val uploadPath: Path = path.resolve(relativeUploadPath)
  def filterFileName(fileName: String): Boolean = fileName.endsWith(".mp4") && !fileName.startsWith(".")
}

case class PreviewConfig(transcode: List[TranscodeSettings])

case class UserConfig(
  adminUsername: String,
  adminPassword: String,
)

case class TranscodeSettings(
  format: String,
  scaleHeight: Int,
  crf: Int
)

sealed trait DeleteMediaOption
case object DeleteFile extends DeleteMediaOption
case object MoveToTrash extends DeleteMediaOption

sealed trait HashingAlgorithm {
  def generateHash(path: Path): String
}

case object PartialHash extends HashingAlgorithm {
  override def generateHash(path: Path): String =
    partialHash(path, 512, data => {
      // sha-1 creates a 160 bit hash (20 bytes)
      val sha1Digest: MessageDigest = MessageDigest.getInstance("SHA-1")
      val digest: Array[Byte]       = sha1Digest.digest(data)

      // we take 10 bytes = 80 bits = 16 base32 characters
      // https://en.wikipedia.org/wiki/Birthday_attack
      Base32.encodeToBase32(digest).substring(0, 16)
    }
  )
}

case class WebServerConfig(
  hostName: String,
  webClientPath: String,
  requestTimeout: FiniteDuration,
  enableAdmin: Boolean,
  uploadSizeLimit: Information,
  defaultNumberOfResults: Int,
  http: Option[HttpConfig],
  https: Option[HttpsConfig],
  jwt: AuthConfig
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

trait ConfigLoader extends Logging {

  import pureconfig._
  import pureconfig.generic.auto._
  import pureconfig.module.squants._
  import pureconfig.generic.semiauto.deriveEnumerationReader

  implicit val hashingAlgorithmReader: ConfigReader[HashingAlgorithm] = deriveEnumerationReader[HashingAlgorithm]
  implicit val deleteMediaOption: ConfigReader[DeleteMediaOption] = deriveEnumerationReader[DeleteMediaOption]

  val config       = ConfigFactory.load()
  val configSource = ConfigSource.fromConfig(config)

  val appConfig = configSource.at("amony").loadOrThrow[AmonyConfig]
}
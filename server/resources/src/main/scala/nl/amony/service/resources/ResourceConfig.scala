package nl.amony.service.resources

import nl.amony.lib.hash.Base32
import nl.amony.lib.hash.PartialHash.partialHash
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveEnumerationReader

import java.nio.file.Path
import java.security.MessageDigest
import scala.concurrent.duration.FiniteDuration

object ResourceConfig {
  case class LocalResourcesConfig(
      id: String,
      private val path: Path,
      private val indexPath: Path,
      relativeUploadPath: Path,
      deleteMedia: DeleteMediaOption,
      scanParallelFactor: Int,
      verifyExistingHashes: Boolean,
      hashingAlgorithm: HashingAlgorithm,
      fragments: FragmentSettings,
      transcode: List[TranscodeSettings],
      extensions: List[String]
  ) {

    def getIndexPath(): Path    = indexPath.toAbsolutePath.normalize()
    lazy val resourcePath: Path = indexPath.toAbsolutePath.normalize().resolve("resources")
    lazy val mediaPath: Path    = path.toAbsolutePath.normalize()
    lazy val uploadPath: Path   = mediaPath.resolve(relativeUploadPath)

    def filterFileName(fileName: String): Boolean =
      extensions.exists(ext => fileName.endsWith(s".$ext")) && !fileName.startsWith(".")
  }

  case class FragmentSettings(
    defaultFragmentLength: FiniteDuration,
    minimumFragmentLength: FiniteDuration,
    maximumFragmentLength: FiniteDuration,
  )

  case class TranscodeSettings(
    format: String,
    scaleHeight: Int,
    crf: Int
  )

  sealed trait DeleteMediaOption
  case object DeleteFile  extends DeleteMediaOption
  case object MoveToTrash extends DeleteMediaOption

  object DeleteMediaOption {
    implicit val deleteMediaOption: ConfigReader[DeleteMediaOption] = deriveEnumerationReader[DeleteMediaOption]
  }

  sealed trait HashingAlgorithm {
    def algorithm: String
    def createHash(path: Path): String
    def encodeHash(bytes: Array[Byte]): String
  }

  object HashingAlgorithm {
    implicit val hashingAlgorithmReader: ConfigReader[HashingAlgorithm] = deriveEnumerationReader[HashingAlgorithm]
  }

  case object PartialHash extends HashingAlgorithm {
    override val algorithm = "SHA-1"
    override def createHash(path: Path): String =
      partialHash(
        file    = path,
        nBytes  = 512,
        hasher  = () => { MessageDigest.getInstance(algorithm) },
        encoder = encodeHash
      )

    override def encodeHash(bytes: Array[Byte]): String =
      Base32.encode(bytes).substring(0, 24)
  }
}

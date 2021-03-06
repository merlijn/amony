package nl.amony.service.media

import nl.amony.lib.hash.Base32
import nl.amony.lib.hash.PartialHash.partialHash

import java.nio.file.Path
import java.security.MessageDigest
import scala.concurrent.duration.FiniteDuration

object MediaConfig {
  case class LocalResourcesConfig(
      private val path: Path,
      private val indexPath: Path,
      private val relativeUploadPath: Path,
      deleteMedia: DeleteMediaOption,
      scanParallelFactor: Int,
      verifyExistingHashes: Boolean,
      hashingAlgorithm: HashingAlgorithm,
      fragments: FragmentSettings,
      transcode: List[TranscodeSettings],
      ffprobeTimeout: FiniteDuration
  ) {

    def getIndexPath(): Path    = indexPath.toAbsolutePath.normalize()
    lazy val resourcePath: Path = indexPath.toAbsolutePath.normalize().resolve("resources")
    lazy val mediaPath: Path    = path.toAbsolutePath.normalize()
    lazy val uploadPath: Path   = mediaPath.resolve(relativeUploadPath)

    def filterFileName(fileName: String): Boolean = fileName.endsWith(".mp4") && !fileName.startsWith(".")
  }

  case class FFMPegConfig(
     scanParallelFactor: Int,
     ffprobeTimeout: FiniteDuration
  )

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

  sealed trait HashingAlgorithm {
    def algorithm: String
    def createHash(path: Path): String
    def encodeHash(bytes: Array[Byte]): String
  }

  case object PartialHash extends HashingAlgorithm {
    override val algorithm = "SHA-1"
    override def createHash(path: Path): String =
      partialHash(
        file    = path,
        nBytes  = 512,
        hasher  = () => { MessageDigest.getInstance(algorithm) },
        encoder = bytes => { Base32.encode(bytes).substring(0, 16) }
      )

    override def encodeHash(bytes: Array[Byte]): String =
      Base32.encode(bytes).substring(0, 24)
  }
}

package nl.amony.service.media

import nl.amony.lib.hash.Base32
import nl.amony.lib.hash.PartialHash.partialHash

import java.nio.file.Path
import java.security.MessageDigest
import scala.concurrent.duration.FiniteDuration

object MediaConfig {
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
      transcode: List[TranscodeSettings],
      ffprobeTimeout: FiniteDuration
  ) {

    lazy val resourcePath: Path = indexPath.resolve("resources")
    lazy val mediaPath: Path    = path.toAbsolutePath.normalize()
    lazy val uploadPath: Path   = path.resolve(relativeUploadPath)

    def filterFileName(fileName: String): Boolean = fileName.endsWith(".mp4") && !fileName.startsWith(".")
  }

  case class TranscodeSettings(
      format: String,
      scaleHeight: Int,
      crf: Int
  )

  sealed trait DeleteMediaOption
  case object DeleteFile  extends DeleteMediaOption
  case object MoveToTrash extends DeleteMediaOption

  sealed trait HashingAlgorithm {
    def generateHash(path: Path): String
  }

  case object PartialHash extends HashingAlgorithm {
    override def generateHash(path: Path): String =
      partialHash(
        path,
        512,
        data => {
          // sha-1 creates a 160 bit hash (20 bytes)
          val sha1Digest: MessageDigest = MessageDigest.getInstance("SHA-1")
          val digest: Array[Byte]       = sha1Digest.digest(data)

          // we take 10 bytes = 80 bits = 16 base32 characters
          // https://en.wikipedia.org/wiki/Birthday_attack
          Base32.encodeToBase32(digest).substring(0, 16)
        }
      )
  }
}

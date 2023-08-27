package nl.amony.service.resources

import nl.amony.lib.hash.Base32
import nl.amony.lib.hash.PartialHash.partialHash
import pureconfig._
import pureconfig.generic.derivation.default._

import java.nio.file.Path
import java.security.MessageDigest
import scala.concurrent.duration.FiniteDuration

object ResourceConfig {

  sealed trait ResourceBucketConfig derives ConfigReader

  case class LocalDirectoryConfig(
      id: String,
      private val path: Path,
      scanParallelFactor: Int,
      verifyExistingHashes: Boolean,
      hashingAlgorithm: HashingAlgorithm,
      relativeResourcePath: Path,
      extensions: List[String]
  ) extends ResourceBucketConfig {

    lazy val writePath: Path    = path.toAbsolutePath.normalize().resolve(relativeResourcePath)
    lazy val resourcePath: Path = path.toAbsolutePath.normalize()

    def filterFileName(fileName: String): Boolean =
      extensions.exists(ext => fileName.endsWith(s".$ext")) && !fileName.startsWith(".")
  }

  case class TranscodeSettings(
    format: String,
    scaleHeight: Int,
    crf: Int
  ) derives ConfigReader

  sealed trait HashingAlgorithm derives ConfigReader {
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
        encoder = encodeHash
      )

    override def encodeHash(bytes: Array[Byte]): String =
      Base32.encode(bytes).substring(0, 24)
  }
}

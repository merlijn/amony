package nl.amony.service.resources

import cats.effect.IO
import nl.amony.lib.hash.Base32
import nl.amony.lib.hash.PartialHash.partialHash
import pureconfig.*
import pureconfig.generic.derivation.default.*

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
      pollInterval: FiniteDuration,
      hashingAlgorithm: HashingAlgorithm,
      relativeResourcePath: Path,
      extensions: List[String]
  ) extends ResourceBucketConfig {

    lazy val cachePath: Path    = path.toAbsolutePath.normalize().resolve(relativeResourcePath)
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
    def createHash(path: Path): IO[String]
    def encodeHash(bytes: Array[Byte]): String
  }

  case object PartialHash extends HashingAlgorithm {
    override val algorithm = "SHA-1"
    override def createHash(path: Path): IO[String] =
      partialHash(
        file      = path,
        nChunks   = 32,
        chunkSize = 32,
        hasher    = () => MessageDigest.getInstance(algorithm),
        encoder   = encodeHash
      )

    override def encodeHash(bytes: Array[Byte]): String =
      Base32.encode(bytes).substring(0, 24) // this is a 24 character hash base 32 = 120 bits
  }
}

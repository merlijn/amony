package nl.amony.service.resources

import cats.effect.IO
import nl.amony.service.resources.util.Base32
import nl.amony.service.resources.util.PartialHash.partialHash
import pureconfig.*
import pureconfig.generic.FieldCoproductHint
import pureconfig.generic.scala3.HintsAwareConfigReaderDerivation.deriveReader

import java.nio.file.Path
import java.security.MessageDigest
import scala.concurrent.duration.FiniteDuration

object ResourceConfig {

  sealed trait ResourceBucketConfig

  object ResourceBucketConfig:
    given FieldCoproductHint[ResourceBucketConfig] = new FieldCoproductHint[ResourceBucketConfig]("type"):
      override def fieldValue(name: String) = name.dropRight("Config".length)

    given ConfigReader[ResourceBucketConfig] = deriveReader
  
  case class ScanConfig(
     enabled: Boolean,
     scanParallelFactor: Int,
     pollInterval: FiniteDuration,
     verifyExistingHashes: Boolean,
     hashingAlgorithm: HashingAlgorithm,
     extensions: List[String],
  ) derives ConfigReader

  case class LocalDirectoryConfig(
     id: String,
     private val path: Path,
     scan: ScanConfig,
     relativeCachePath: Path,
     relativeUploadPath: Path,
     
  ) extends ResourceBucketConfig {

    lazy val cachePath: Path    = path.toAbsolutePath.normalize().resolve(relativeCachePath)
    lazy val resourcePath: Path = path.toAbsolutePath.normalize()
    lazy val uploadPath: Path   = path.toAbsolutePath.normalize().resolve(relativeUploadPath)
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

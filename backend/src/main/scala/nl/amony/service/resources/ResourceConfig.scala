package nl.amony.service.resources

import java.nio.file.Path
import java.security.MessageDigest

import scala.concurrent.duration.FiniteDuration

import cats.effect.IO
import pureconfig.*
import pureconfig.generic.FieldCoproductHint
import pureconfig.generic.scala3.HintsAwareConfigReaderDerivation.deriveReader
import sqids.Sqids

import nl.amony.service.resources.util.Base32
import nl.amony.service.resources.util.PartialHash.partialHash

object ResourceConfig {

  sealed trait ResourceBucketConfig

  object ResourceBucketConfig:
    given FieldCoproductHint[ResourceBucketConfig] = new FieldCoproductHint[ResourceBucketConfig]("type"):
      override def fieldValue(name: String) = name.dropRight("Config".length)

    given ConfigReader[ResourceBucketConfig] = deriveReader

  case class ScanConfig(
    enabled: Boolean,
    syncOnStartup: Boolean,
    newFilesOwner: String,
    scanParallelFactor: Int,
    pollInterval: FiniteDuration,
    verifyExistingHashes: Boolean,
    extensions: List[String]
  ) derives ConfigReader

  case class LocalDirectoryConfig(
    id: String,
    private val path: Path,
    sync: ScanConfig,
    hashingAlgorithm: HashingAlgorithm,
    relativeCachePath: Path,
    relativeUploadPath: Path
  ) extends ResourceBucketConfig {

    val random                  = new scala.util.Random()
    lazy val cachePath: Path    = path.toAbsolutePath.normalize().resolve(relativeCachePath)
    lazy val resourcePath: Path = path.toAbsolutePath.normalize()
    lazy val uploadPath: Path   = path.toAbsolutePath.normalize().resolve(relativeUploadPath)

    def filterFiles(path: Path) = {
      val fileName = path.getFileName.toString
      sync.extensions.exists(ext => fileName.endsWith(s".$ext")) && !fileName.startsWith(".")
    }

    def filterDirectory(path: Path) = {
      val fileName = path.getFileName.toString
      !fileName.startsWith(".") && path != uploadPath
    }

    def generateId() = Base32.encode(random.nextBytes(15)).substring(0, 24)
  }

  case class TranscodeSettings(format: String, scaleHeight: Int, crf: Int) derives ConfigReader

  sealed trait HashingAlgorithm derives ConfigReader {
    def algorithm: String
    def newDigest(): MessageDigest = MessageDigest.getInstance(algorithm)
    def createHash(path: Path): IO[String]
    def encodeHash(bytes: Array[Byte]): String
  }

  case object PartialHash extends HashingAlgorithm {
    override val algorithm                          = "SHA-1"
    override def createHash(path: Path): IO[String] =
      partialHash(file = path, nChunks = 32, chunkSize = 32, digestFn = () => newDigest(), encoder = encodeHash)

    override def encodeHash(bytes: Array[Byte]): String = Base32.encode(bytes).substring(0, 24) // this is a 24 character hash base 32 = 120 bits
  }
}

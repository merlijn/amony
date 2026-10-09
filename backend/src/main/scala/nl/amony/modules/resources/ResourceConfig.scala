package nl.amony.modules.resources

import java.nio.file.Path
import java.security.MessageDigest
import scala.concurrent.duration.FiniteDuration
import scala.language.adhocExtensions

import cats.effect.IO
import pureconfig.*
import pureconfig.error.CannotConvert
import pureconfig.generic.FieldCoproductHint
import pureconfig.generic.scala3.HintsAwareConfigReaderDerivation.deriveReader

import nl.amony.lib.files.GlobPatterns
import nl.amony.lib.hash.Base32
import nl.amony.lib.hash.PartialHash.partialHash
import nl.amony.modules.auth.api.Role
import nl.amony.modules.resources.ResourceConfig.ResourceBucketConfig
import nl.amony.modules.resources.api.{ImageFormat, ResourceId}

enum ResolutionPickingStrategy(val configName: String):
  case RoundUp      extends ResolutionPickingStrategy("round-up")
  case RoundDown    extends ResolutionPickingStrategy("round-down")
  case RoundNearest extends ResolutionPickingStrategy("round-nearest")

object ResolutionPickingStrategy:
  given ConfigReader[ResolutionPickingStrategy] =
    ConfigReader[String].emap { name =>
      values.find(_.configName == name).toRight(
        CannotConvert(name, "ResolutionPickingStrategy", s"Expected one of: ${values.map(_.configName).mkString(", ")}")
      )
    }

given ConfigReader[ImageFormat] =
  ConfigReader[String].emap { name =>
    ImageFormat.fromName(name).toRight(
      CannotConvert(name, "ImageFormat", s"Expected one of: ${ImageFormat.values.map(_.configName).mkString(", ")}")
    )
  }

/**
 * Pureconfig only ships a `Map[String, A]` reader, so this adapts the `format-options` map to
 * `ImageFormat` keys, failing fast on an unknown format name.
 */
given ConfigReader[Map[ImageFormat, List[String]]] =
  ConfigReader[Map[String, List[String]]].emap { raw =>
    raw.foldLeft[Either[CannotConvert, Map[ImageFormat, List[String]]]](Right(Map.empty)) {
      case (result, (name, args)) =>
        for
          options <- result
          format  <- ImageFormat.fromName(name).toRight(
                       CannotConvert(name, "ImageFormat", s"Expected one of: ${ImageFormat.values.map(_.configName).mkString(", ")}")
                     )
        yield options.updated(format, args)
    }
  }

case class ThumbnailConfig(
  allowedResolutions: List[Int],
  defaultResolution: Int,
  supportedImageFormats: List[ImageFormat],
  supportedVideoFormats: List[String]           = List("mp4"),
  resolutionStepDown: Int,
  resolutionPickingStrategy: ResolutionPickingStrategy,
  formatOptions: Map[ImageFormat, List[String]] = Map.empty
) derives ConfigReader

case class ResourceConfig(previews: ThumbnailConfig, defaultBucket: ResourceBucketConfig) derives ConfigReader

object ResourceConfig {

  sealed trait ResourceBucketConfig:
    def id: String

    /** When set, only users holding this role (admins always) can see the bucket. */
    def requiredRole: Option[Role]

  object ResourceBucketConfig:
    given FieldCoproductHint[ResourceBucketConfig] =
      new FieldCoproductHint[ResourceBucketConfig]("type"):
        override def fieldValue(name: String) = name.dropRight("Config".length)

    given ConfigReader[ResourceBucketConfig] = deriveReader

  case class ScanConfig(
    enabled: Boolean,
    syncOnStartup: Boolean,
    newFilesOwner: String,
    scanParallelFactor: Int,
    pollInterval: FiniteDuration,
    includePatterns: List[String],
    excludePatterns: List[String]
  ) derives ConfigReader

  case class LocalDirectoryConfig(
    id: String,
    override val requiredRole: Option[Role] = None,
    path: Path,
    sync: ScanConfig,
    hashingAlgorithm: HashingAlgorithm,
    relativeUploadPath: Path,
    generatePreviewsOnAdd: Boolean          = false
  ) extends ResourceBucketConfig {

    val random                  = new scala.util.Random()
    lazy val amonyPath: Path    = path.toAbsolutePath.normalize().resolve(".amony")
    lazy val bucketIdPath: Path = amonyPath.resolve("bucketId")
    lazy val cachePath: Path    = amonyPath.resolve("cache")
    lazy val resourcePath: Path = path.toAbsolutePath.normalize()
    lazy val uploadPath: Path   = path.toAbsolutePath.normalize().resolve(relativeUploadPath)

    private lazy val includes = GlobPatterns.unsafeParse(sync.includePatterns)
    private lazy val excludes = GlobPatterns.unsafeParse(sync.excludePatterns)

    private def relativePath(path: Path): String = resourcePath.relativize(path).toString

    def filterFiles(path: Path): Boolean = {
      val relative = relativePath(path)
      includes.matches(relative) && !excludes.matches(relative)
    }

    def filterDirectory(path: Path): Boolean =
      path == resourcePath || (path != uploadPath && path != amonyPath && !excludes.matches(relativePath(path)))

    def generateId(): ResourceId = ResourceId(Base32.encode(random.nextBytes(15)).substring(0, 24))
  }

  case class TranscodeSettings(format: String, scaleHeight: Int, crf: Int) derives ConfigReader

  sealed trait HashingAlgorithm derives ConfigReader {
    def name: String
    def algorithm: String
    def newDigest(): MessageDigest = MessageDigest.getInstance(algorithm)
    def createHash(path: Path): IO[String]
    def encodeHash(bytes: Array[Byte]): String
  }

  object HashingAlgorithm {
    val all: List[HashingAlgorithm]                      = List(PartialHash)
    def fromName(name: String): Option[HashingAlgorithm] = all.find(_.name == name)
  }

  case object PartialHash extends HashingAlgorithm {
    override val name                               = "partial-hash"
    override val algorithm                          = "SHA-1"
    override def createHash(path: Path): IO[String] =
      partialHash(file = path, nChunks = 32, chunkSize = 32, digestFn = () => newDigest(), encoder = encodeHash)

    override def encodeHash(bytes: Array[Byte]): String = Base32.encode(bytes).substring(0, 24) // this is a 24 character hash base 32 = 120 bits
  }
}

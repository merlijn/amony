package nl.amony.modules.resources.api

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import scala.concurrent.duration.FiniteDuration
import scala.language.adhocExtensions

import cats.effect.IO
import cats.implicits.*
import pureconfig.*
import pureconfig.generic.FieldCoproductHint
import pureconfig.generic.scala3.HintsAwareConfigReaderDerivation.deriveReader

import nl.amony.lib.files.GlobPatterns
import nl.amony.lib.hash.Base32
import nl.amony.lib.hash.PartialHash.partialHash
import nl.amony.modules.auth.api.Role

sealed trait ResourceBucketConfig:
  def id: String

  /** When set, only users holding this role (admins always) can see the bucket. */
  def requiredRole: Option[Role]

  /** Validates this configuration on its own and returns it with normalized (absolute) paths. */
  def validate(): IO[Either[String, ResourceBucketConfig]]

  /** Whether this bucket and the other one cover (part of) the same storage. */
  def overlaps(other: ResourceBucketConfig): Boolean

  /** Whether an existing bucket with this configuration may be changed into the updated one. */
  def checkUpdateAllowed(updated: ResourceBucketConfig): Either[String, Unit]

object ResourceBucketConfig:
  given FieldCoproductHint[ResourceBucketConfig] =
    new FieldCoproductHint[ResourceBucketConfig]("type"):
      override def fieldValue(name: String) = name.dropRight("Config".length)

  given ConfigReader[ResourceBucketConfig] = deriveReader

case class ScanConfig(
  enabled: Boolean,
  syncOnStartup: Boolean,
  newFilesOwner: String,
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
  lazy val uploadPath: Path   = path.toAbsolutePath.normalize().resolve(relativeUploadPath).normalize()

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

  private def realPath: Path = if Files.exists(resourcePath) then resourcePath.toRealPath() else resourcePath

  override def validate(): IO[Either[String, ResourceBucketConfig]] = IO.blocking {
    val invalidPatterns = (GlobPatterns.parse(sync.includePatterns) >> GlobPatterns.parse(sync.excludePatterns)).left.toOption

    if !Files.isDirectory(resourcePath) then Left(s"Path '$resourcePath' does not exist or is not a directory")
    else if !Files.isReadable(resourcePath) then Left(s"Path '$resourcePath' is not readable")
    else if relativeUploadPath.isAbsolute then Left("Upload path must be relative to the bucket path")
    else if !uploadPath.startsWith(resourcePath) || uploadPath == resourcePath then Left("Upload path must be a sub directory of the bucket path")
    else if sync.includePatterns.isEmpty then Left("At least one include pattern is required")
    else if invalidPatterns.isDefined then Left(invalidPatterns.get)
    else if sync.pollInterval.toSeconds < 1 then Left("Poll interval must be at least 1 second")
    else if sync.newFilesOwner.isBlank then Left("New files owner is required")
    else Right(copy(path = resourcePath))
  }

  override def overlaps(other: ResourceBucketConfig): Boolean = other match
    case o: LocalDirectoryConfig => realPath.startsWith(o.realPath) || o.realPath.startsWith(realPath)

  override def checkUpdateAllowed(updated: ResourceBucketConfig): Either[String, Unit] = updated match
    case u: LocalDirectoryConfig =>
      Either.cond(u.hashingAlgorithm == hashingAlgorithm, (), "The hashing algorithm of an existing bucket cannot be changed")
}

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

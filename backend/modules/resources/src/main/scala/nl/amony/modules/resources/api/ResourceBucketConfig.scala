package nl.amony.modules.resources.api

import java.net.URI
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*
import scala.language.adhocExtensions

import cats.effect.IO
import cats.implicits.*
import pureconfig.*
import pureconfig.generic.FieldCoproductHint
import pureconfig.generic.scala3.HintsAwareConfigReaderDerivation.deriveReader

import nl.amony.modules.auth.api.Role
import nl.amony.modules.resources.util.Base32
import nl.amony.platform.files.GlobPatterns

final case class ResourceBucketConfig(
  id: String,
  /** When set, only users holding this role (admins always) can see the bucket. */
  requiredRole: Option[Role]     = None,
  generatePreviewsOnAdd: Boolean = false,
  storage: BucketStorageConfig
) derives ConfigReader:

  private val random = new scala.util.Random()

  def generateId(): ResourceId = ResourceId(Base32.encode(random.nextBytes(15)).substring(0, 24))

  /** Validates this configuration on its own and returns it with normalized storage settings. */
  def validate(): IO[Either[String, ResourceBucketConfig]] =
    storage.validate().map(_.map(normalized => copy(storage = normalized)))

  /** Whether this bucket and the other one cover (part of) the same storage. */
  def overlaps(other: ResourceBucketConfig): Boolean =
    storage.overlaps(other.storage)

  /** Whether an existing bucket with this configuration may be changed into the updated one. */
  def checkUpdateAllowed(updated: ResourceBucketConfig): Either[String, Unit] =
    storage.checkUpdateAllowed(updated.storage)

sealed trait BucketStorageConfig:
  def validate(): IO[Either[String, BucketStorageConfig]]
  def overlaps(other: BucketStorageConfig): Boolean
  def checkUpdateAllowed(updated: BucketStorageConfig): Either[String, Unit]

object BucketStorageConfig:
  given FieldCoproductHint[BucketStorageConfig] =
    new FieldCoproductHint[BucketStorageConfig]("type"):
      override def fieldValue(name: String) = name.dropRight("StorageConfig".length)

  given ConfigReader[BucketStorageConfig] = deriveReader

case class ScanConfig(
  enabled: Boolean,
  syncOnStartup: Boolean,
  newFilesOwner: String,
  pollInterval: FiniteDuration,
  includePatterns: List[String],
  excludePatterns: List[String]
) derives ConfigReader

case class LocalDirectoryStorageConfig(
  path: Path,
  sync: ScanConfig,
  relativeUploadPath: Path = Path.of("_upload")
) extends BucketStorageConfig {

  lazy val amonyPath: Path    = path.toAbsolutePath.normalize().resolve(".amony")
  lazy val bucketIdPath: Path = amonyPath.resolve("bucketId")
  lazy val cachePath: Path    = amonyPath.resolve("cache")
  lazy val tempPath: Path     = amonyPath.resolve("temp")
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

  private def realPath: Path = if Files.exists(resourcePath) then resourcePath.toRealPath() else resourcePath

  override def validate(): IO[Either[String, BucketStorageConfig]] = IO.blocking {
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

  override def overlaps(other: BucketStorageConfig): Boolean = other match
    case o: LocalDirectoryStorageConfig => realPath.startsWith(o.realPath) || o.realPath.startsWith(realPath)
    case _                              => false

  override def checkUpdateAllowed(updated: BucketStorageConfig): Either[String, Unit] = updated match
    case _: LocalDirectoryStorageConfig => Right(())
    case _                              => Left("The storage type of an existing bucket cannot be changed")
}

case class S3StorageConfig(
  bucket: String,
  region: String,
  endpoint: Option[URI]           = None,
  prefix: Option[String]          = None,
  pathStyleAccess: Boolean        = false,
  presignedUrlTtl: FiniteDuration = 15.minutes,
  accessKeyId: Option[String]     = None,
  secretAccessKey: Option[String] = None
) extends BucketStorageConfig {

  lazy val normalizedPrefix: Option[String] =
    prefix.map(_.strip().stripPrefix("/").stripSuffix("/")).filter(_.nonEmpty)

  override def validate(): IO[Either[String, BucketStorageConfig]] = IO.pure {
    val cleanBucket = bucket.strip()
    val cleanRegion = region.strip()
    val cleanKeyId  = accessKeyId.map(_.strip()).filter(_.nonEmpty)
    val cleanSecret = secretAccessKey.map(_.strip()).filter(_.nonEmpty)
    val badEndpoint = endpoint.filter(u => !Set("http", "https").contains(u.getScheme) || Option(u.getHost).forall(_.isBlank))

    if cleanBucket.isBlank then Left("S3 bucket name is required")
    else if cleanRegion.isBlank then Left("S3 region is required")
    else if badEndpoint.isDefined then Left(s"S3 endpoint must be a valid http or https URL: ${badEndpoint.get}")
    else if cleanKeyId.isDefined != cleanSecret.isDefined then Left("Both access key id and secret access key must be provided together")
    else if presignedUrlTtl < 1.second then Left("Presigned URL TTL must be at least 1 second")
    else if presignedUrlTtl > 7.days then Left("Presigned URL TTL must be at most 7 days")
    else
      Right(
        copy(
          bucket          = cleanBucket,
          region          = cleanRegion,
          prefix          = normalizedPrefix,
          accessKeyId     = cleanKeyId,
          secretAccessKey = cleanSecret
        )
      )
  }

  override def overlaps(other: BucketStorageConfig): Boolean = other match
    case o: S3StorageConfig =>
      bucket.strip() == o.bucket.strip() &&
      region.strip() == o.region.strip() &&
      endpoint == o.endpoint &&
      S3StorageConfig.prefixesOverlap(normalizedPrefix, o.normalizedPrefix)
    case _                  => false

  override def checkUpdateAllowed(updated: BucketStorageConfig): Either[String, Unit] = updated match
    case _: S3StorageConfig => Right(())
    case _                  => Left("The storage type of an existing bucket cannot be changed")
}

object S3StorageConfig:
  private def prefixesOverlap(a: Option[String], b: Option[String]): Boolean = (a, b) match
    case (None, _)            => true
    case (_, None)            => true
    case (Some(p1), Some(p2)) =>
      p1 == p2 || p1.startsWith(s"$p2/") || p2.startsWith(s"$p1/")

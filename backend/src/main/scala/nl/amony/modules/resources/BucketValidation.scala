package nl.amony.modules.resources

import java.nio.file.{Files, Path}

import cats.effect.IO
import cats.implicits.*

import nl.amony.lib.files.GlobPatterns
import nl.amony.modules.resources.ResourceConfig.{LocalDirectoryConfig, ResourceBucketConfig}

object BucketValidation:

  private val idPattern = "[a-z0-9_-]{1,64}".r

  private def realPath(path: Path): Path =
    if Files.exists(path) then path.toRealPath() else path.toAbsolutePath.normalize()

  private def overlaps(a: Path, b: Path): Boolean = a.startsWith(b) || b.startsWith(a)

  /** Validates a bucket configuration against the other (already existing) buckets. */
  def validate(config: ResourceBucketConfig, others: List[ResourceBucketConfig]): IO[Either[String, ResourceBucketConfig]] =
    config match
      case c: LocalDirectoryConfig => IO.blocking(validateLocalDirectory(c, others))

  private def validateLocalDirectory(config: LocalDirectoryConfig, others: List[ResourceBucketConfig]): Either[String, ResourceBucketConfig] =
    lazy val resourcePath = config.resourcePath
    lazy val uploadPath   = config.uploadPath

    lazy val invalidPatterns = (GlobPatterns.parse(config.sync.includePatterns) >> GlobPatterns.parse(config.sync.excludePatterns)).left.toOption

    lazy val overlapping = others.collectFirst {
      case other: LocalDirectoryConfig if other.id != config.id && overlaps(realPath(other.resourcePath), realPath(resourcePath)) => other.id
    }

    if !idPattern.matches(config.id) then Left("Bucket id must be 1-64 characters of lowercase letters, digits, '-' or '_'")
    else if !Files.isDirectory(resourcePath) then Left(s"Path '$resourcePath' does not exist or is not a directory")
    else if !Files.isReadable(resourcePath) then Left(s"Path '$resourcePath' is not readable")
    else if config.relativeUploadPath.isAbsolute then Left("Upload path must be relative to the bucket path")
    else if !uploadPath.startsWith(resourcePath) || uploadPath == resourcePath then Left("Upload path must be a sub directory of the bucket path")
    else if config.sync.includePatterns.isEmpty then Left("At least one include pattern is required")
    else if invalidPatterns.isDefined then Left(invalidPatterns.get)
    else if config.sync.pollInterval.toSeconds < 1 then Left("Poll interval must be at least 1 second")
    else if config.sync.scanParallelFactor < 1 then Left("Scan parallel factor must be at least 1")
    else if config.sync.newFilesOwner.isBlank then Left("New files owner is required")
    else if overlapping.isDefined then Left(s"Path overlaps with the path of bucket '${overlapping.get}'")
    else Right(config)

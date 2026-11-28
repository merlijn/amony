package nl.amony.modules.resources.impl

import java.net.URI
import java.nio.file.Path
import java.time.Instant

import cats.data.EitherT
import cats.effect.IO

import nl.amony.modules.resources.api.*

/** Identifies an object inside a [[ResourceStore]]. */
enum StoreKey:
  case Resource(path: ResourcePath)
  case Derived(resourceId: ResourceId, name: String)
  case Staged(id: StagingId)

object StoreKey:

  private def scaleSuffix(width: Option[Int], height: Option[Int]): String =
    width.map(w => s"w$w").orElse(height.map(h => s"h$h")).getOrElse("orig")

  def derived(resourceId: ResourceId, contentVersion: String, operation: ResourceOperation): StoreKey.Derived =
    val name = operation match
      case VideoFragment(width, height, start, end)                         =>
        s"${resourceId}_${contentVersion}_$start-${end}_${scaleSuffix(width, height)}.mp4"
      case VideoThumbnail(width, height, timestamp, format)                 =>
        s"${resourceId}_${contentVersion}_${timestamp}_${scaleSuffix(width, height)}.${format.extension}"
      case ImageThumbnail(width, height, format)                            =>
        s"${resourceId}_${contentVersion}_${scaleSuffix(width, height)}.${format.extension}"
      case VideoThumbnailTile(spritePixels, intervalInMillis, index, token) =>
        VideoThumbnailTile.spriteFileName(resourceId, contentVersion, spritePixels, intervalInMillis, index, token)
      case VideoThumbnailTileVtt(spritePixels, intervalInMillis, token)     =>
        VideoThumbnailTile.manifestFileName(resourceId, contentVersion, spritePixels, intervalInMillis, token)
      case AddFastStart(_)                                                  =>
        throw new IllegalArgumentException("AddFastStart does not produce a derived output file")
    StoreKey.Derived(resourceId, name)

/** Where a stored object can be read by the media compute layer. */
enum MediaSource:
  case LocalFile(path: Path)
  case RemoteUrl(uri: URI)

  def toUri: URI = this match
    case LocalFile(path) => path.toUri
    case RemoteUrl(uri)  => uri

/** Metadata of an object stored in a [[ResourceStore]]. */
final case class StoredResource(
  path: ResourcePath,
  size: Long,
  timeLastModified: Long,
  fileKey: Option[String]
)

/**
 * Storage backend for the resources, derived artifacts and staged uploads of a bucket. A local file system and object
 * storage are used interchangeably through this interface.
 */
trait ResourceStore:

  /** Returns the content of a stored object for serving, or `None` when it does not exist. */
  def content(key: StoreKey, contentType: Option[String], eTag: Option[String]): IO[Option[ResourceContent]]

  /** Checks whether an object exists at `key`. */
  def exists(key: StoreKey): IO[Boolean]

  /** Writes or atomically replaces the object at `key` with the bytes from `source`. */
  def put(key: StoreKey, contentType: Option[String], source: fs2.Stream[IO, Byte]): EitherT[IO, String, Unit]

  /** Writes or atomically replaces the object at `key` with in-memory bytes. */
  def putBytes(key: StoreKey, contentType: Option[String], bytes: Array[Byte]): IO[Unit]

  /** Deletes the object at `key`. */
  def delete(key: StoreKey): IO[Unit]

  /**
   * Moves a staged object to `target`, returning its metadata. Fails if `target` already exists unless `overwrite` is
   * `true`, in which case it is replaced atomically.
   */
  def promote(stagingId: StagingId, target: ResourcePath, overwrite: Boolean = false): EitherT[IO, String, StoredResource]

  /** Returns a readable handle for the compute layer, or `None` when the resource does not exist. */
  def openSource(path: ResourcePath): IO[Option[MediaSource]]

  /** Returns the stored metadata of a resource, or `None` when it does not exist. */
  def stat(path: ResourcePath): IO[Option[StoredResource]]

  /** Deletes all derived artifacts belonging to a resource. */
  def deleteDerived(resourceId: ResourceId): IO[Unit]

  /** Deletes staged objects last modified before `cutoffTime`. */
  def deleteStaged(cutoffTime: Instant): IO[Unit]

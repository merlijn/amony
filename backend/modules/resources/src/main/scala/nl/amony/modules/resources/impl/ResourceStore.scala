package nl.amony.modules.resources.impl

import java.net.URI
import java.nio.file.Path

import cats.data.EitherT
import cats.effect.IO

import nl.amony.modules.resources.api.*

/** Identifies an object inside a [[ResourceStore]]. */
enum StoreKey:
  case Resource(path: ResourcePath)
  case Derived(resourceId: ResourceId, name: String)

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
 * Storage backend for the resources and derived artifacts of a bucket. A local file system and object storage are used
 * interchangeably through this interface.
 */
trait ResourceStore:

  /** Returns the content of a stored object for serving, or `None` when it does not exist. */
  def content(key: StoreKey, contentType: Option[String], eTag: Option[String]): IO[Option[ResourceContent]]

  /** Writes or atomically replaces the object at `key` with the contents of `source`. */
  def put(key: StoreKey, contentType: Option[String], source: Path): EitherT[IO, String, StoredResource]

  /** Writes or atomically replaces the object at `key` with in-memory bytes. */
  def putBytes(key: StoreKey, contentType: Option[String], bytes: Array[Byte]): IO[Unit]

  /** Deletes the object at `key`. */
  def delete(key: StoreKey): IO[Unit]

  /** Stores a new resource at a unique path derived from `fileName`, returning its metadata. */
  def putUnique(fileName: String, source: Path): EitherT[IO, String, StoredResource]

  /** Returns a readable handle for the compute layer, or `None` when the resource does not exist. */
  def openSource(path: ResourcePath): IO[Option[MediaSource]]

  /** Returns the stored metadata of a resource, or `None` when it does not exist. */
  def stat(path: ResourcePath): IO[Option[StoredResource]]

  /** Deletes all derived artifacts belonging to a resource. */
  def deleteDerived(resourceId: ResourceId): IO[Unit]

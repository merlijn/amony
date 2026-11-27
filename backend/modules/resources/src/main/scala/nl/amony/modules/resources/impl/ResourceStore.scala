package nl.amony.modules.resources.impl

import java.net.URI
import java.nio.file.Path

import cats.data.EitherT
import cats.effect.IO

import nl.amony.modules.resources.api.{ResourceContent, ResourcePath}

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
 * Storage backend for the resources of a bucket. A local file system and object storage are used interchangeably
 * through this interface.
 */
trait ResourceStore:

  /** Returns the content of a resource for serving, or `None` when the object does not exist. */
  def content(path: ResourcePath, contentType: Option[String], eTag: Option[String]): IO[Option[ResourceContent]]

  /** Returns a readable handle for the compute layer, or `None` when the object does not exist. */
  def openSource(path: ResourcePath): IO[Option[MediaSource]]

  /** Returns the stored metadata of a resource, or `None` when the object does not exist. */
  def stat(path: ResourcePath): IO[Option[StoredResource]]

  /** Stores a file at a unique path derived from `fileName`, returning its metadata. */
  def put(fileName: String, source: Path): EitherT[IO, String, StoredResource]

  /** Replaces an existing stored resource with the contents of `source`, returning its updated metadata. */
  def replace(path: ResourcePath, source: Path): EitherT[IO, String, StoredResource]

  /** Deletes a stored resource. */
  def delete(path: ResourcePath): IO[Unit]

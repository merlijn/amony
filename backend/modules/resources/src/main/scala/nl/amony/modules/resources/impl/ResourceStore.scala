package nl.amony.modules.resources.impl

import java.nio.file.Path

import cats.effect.IO

import nl.amony.modules.resources.api.{ResourceContent, ResourceInfo, StagingId}

/** Metadata of an object stored in a [[ResourceStore]]. */
final case class StoredResource(
  path: String,
  size: Long,
  timeLastModified: Long,
  fileKey: Option[String]
)

/**
 * Storage backend for the resources of a bucket. A local file system and object storage are used interchangeably
 * through this interface.
 */
trait ResourceStore:

  /** Returns the content of a resource, or `None` when the object does not exist. */
  def content(info: ResourceInfo, eTag: Option[String]): IO[Option[ResourceContent]]

  /**
   * Makes a locally staged file available to the store, so that [[commit]] can move it into place. Stores that share a
   * file system with the staging area may leave this a no-op.
   */
  def stage(stagingId: StagingId, file: Path): IO[Unit]

  /** Moves a staged file to its final location, resolving a unique name, and returns its metadata. */
  def commit(stagingId: StagingId, fileName: String): IO[Either[String, StoredResource]]

  /** Discards a staged file. */
  def discard(stagingId: StagingId): IO[Unit]

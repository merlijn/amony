package nl.amony.modules.resources.api

import java.time.Instant

import cats.effect.IO

enum BucketError:
  case NotFound(bucketId: BucketId)
  case AlreadyExists(bucketId: BucketId)
  case Modified(bucketId: BucketId)
  case Invalid(message: String)
  case NotEmpty(bucketId: BucketId, resourceCount: Long)

/** A bucket configuration as stored, with the time of its last change (used for optimistic locking). */
case class StoredBucket(config: ResourceBucketConfig, updatedAt: Instant)

trait BucketRegistry:
  def get(bucketId: BucketId): IO[Option[ResourceBucket]]
  def all: IO[List[ResourceBucket]]
  def getConfig(bucketId: BucketId): IO[Option[StoredBucket]]
  def allConfigs: IO[List[StoredBucket]]
  def create(config: ResourceBucketConfig): IO[Either[BucketError, Unit]]

  /** Updates a bucket, unless it was changed since `expectedUpdatedAt`. */
  def update(config: ResourceBucketConfig, expectedUpdatedAt: Instant): IO[Either[BucketError, Unit]]

  /** Deletes a bucket from the database and search index. Media files are never deleted. */
  def delete(bucketId: BucketId, force: Boolean): IO[Either[BucketError, Unit]]

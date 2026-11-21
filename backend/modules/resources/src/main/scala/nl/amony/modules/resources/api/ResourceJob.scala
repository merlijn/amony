package nl.amony.modules.resources.api

import io.circe.Codec

import nl.amony.modules.auth.api.UserId
import nl.amony.platform.messagebus.JobTopicKey

/** A durable, at-least-once job on a resource. Handlers must be idempotent. */
sealed trait ResourceJob derives Codec:

  def bucketId: BucketId
  def resourceId: ResourceId

  /** Jobs for the same resource share a partition, so they never run concurrently or out of order. */
  final def key: String = s"$bucketId:$resourceId"

/** Replaces a non-streamable file with a remux and refreshes the resource metadata. */
final case class FixStreamability(bucketId: BucketId, resourceId: ResourceId) extends ResourceJob derives Codec

/** Finishes an upload that was already staged in the bucket's temp directory. */
final case class UploadResource(
  bucketId: BucketId,
  resourceId: ResourceId,
  userId: UserId,
  fileName: String,
  stagingId: StagingId
) extends ResourceJob derives Codec

object ResourceJob:

  /** Format used to scope a job to the bucket that owns it. */
  def bucketOf(messageKey: String): Option[BucketId] =
    val separator = messageKey.lastIndexOf(':')
    if separator <= 0 then None else Some(BucketId(messageKey.substring(0, separator)))

  given JobTopicKey[ResourceJob] = JobTopicKey("resource-jobs", partitions = 8)

package nl.amony.modules.resources.api

import io.circe.Codec

import nl.amony.platform.messagebus.CommandTopicKey

/** A durable, at-least-once operation on a resource. Handlers must be idempotent. */
sealed trait ResourceCommand derives Codec:

  def bucketId: BucketId
  def resourceId: ResourceId

  /** Commands for the same resource share a partition, so they never run concurrently or out of order. */
  final def key: String = s"$bucketId:$resourceId"

/** Replaces a non-streamable file with a prepared remux, then refreshes the resource metadata. */
final case class FixStreamability(bucketId: BucketId, resourceId: ResourceId, tempFile: Option[String]) extends ResourceCommand derives Codec

object ResourceCommand:

  /** Format used to scope a command to the bucket that owns it, e.g. for authorization of the status endpoint. */
  def bucketOf(messageKey: String): Option[BucketId] =
    val separator = messageKey.lastIndexOf(':')
    if separator <= 0 then None else Some(BucketId(messageKey.substring(0, separator)))

  given CommandTopicKey[ResourceCommand] = CommandTopicKey("resource-commands", partitions = 8)

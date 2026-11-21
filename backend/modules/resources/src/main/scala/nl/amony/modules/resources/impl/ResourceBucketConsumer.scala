package nl.amony.modules.resources.impl

import cats.effect.IO

import nl.amony.modules.resources.api.{BucketId, ResourceBucket, ResourceEvent, ResourceJob}

/**
 * The module-internal, consumer-facing view of a bucket. It is a bucket plus the lifecycle operations the background
 * event and job consumers need; those are intentionally absent from [[ResourceBucket]].
 */
trait ResourceBucketConsumer extends ResourceBucket:

  def processEvent(event: ResourceEvent): IO[Unit]

  def processJob(job: ResourceJob): IO[Either[String, IO[Either[String, Unit]]]]

/** Looks up the consumer-facing view of a bucket. */
trait BucketConsumerRegistry:
  def getConsumer(bucketId: BucketId): IO[Option[ResourceBucketConsumer]]

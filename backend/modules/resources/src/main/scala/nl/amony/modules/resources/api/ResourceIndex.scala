package nl.amony.modules.resources.api

import cats.effect.IO
import fs2.Stream

trait ResourceIndex:
  def reindex(bucket: ResourceBucket): IO[Unit]
  def nonStreamable(bucketId: BucketId): Stream[IO, ResourceId]

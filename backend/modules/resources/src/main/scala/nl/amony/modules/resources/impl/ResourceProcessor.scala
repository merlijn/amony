package nl.amony.modules.resources.impl

import cats.effect.IO

import nl.amony.modules.resources.api.{ResourceEvent, ResourceJob}

/** Routes events and jobs to the bucket that owns them. */
class ResourceProcessor(registry: BucketConsumerRegistry):

  def processEvent(event: ResourceEvent): IO[Unit] =
    registry.getConsumer(event.bucketId).flatMap(_.fold(IO.unit)(_.processEvent(event)))

  def processJob(job: ResourceJob): IO[Either[String, IO[Either[String, Unit]]]] =
    registry.getConsumer(job.bucketId).flatMap:
      case Some(consumer) => consumer.processJob(job)
      case None           => IO.pure(Left(s"No bucket '${job.bucketId}'"))

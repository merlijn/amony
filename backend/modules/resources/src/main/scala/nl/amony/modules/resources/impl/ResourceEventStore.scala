package nl.amony.modules.resources.impl

import cats.effect.IO
import scribe.Logging
import skunk.Session

import nl.amony.modules.resources.api.*
import nl.amony.modules.resources.dal.ResourceDatabase
import nl.amony.platform.messagebus.MessageTopic

/** Applies resource events to the database and the event topic. */
trait ResourceEventStore:
  def processEvent(event: ResourceEvent): IO[Unit]

final class DatabaseResourceEventStore(
  bucketId: BucketId,
  db: ResourceDatabase,
  topic: MessageTopic[ResourceEvent],
  invalidateDerived: ResourceId => IO[Unit]
) extends ResourceEventStore,
      Logging:

  private def applyEventToDb(event: ResourceEvent)(using Option[Session[IO]]): IO[Unit] = event match {
    case ResourceAdded(resource)                                   => db.insertResource(resource)
    case ResourceDeleted(resource)                                 => db.deleteResource(bucketId, resource.resourceId)
    case ResourceMoved(_, id, _, newPath)                          => db.move(bucketId, id, newPath)
    case ResourceFileMetaChanged(_, id, lastModifiedTime, fileKey) => db.getResourceById(bucketId, id).flatMap {
        case Some(resource) =>
          db.upsertResource(resource.copy(timeLastModified = Some(lastModifiedTime), fileKey = fileKey.orElse(resource.fileKey)))
        case None           => IO.unit
      }
    case ResourceModified(resource)                                => invalidateDerived(resource.resourceId) >> db.upsertResource(resource)
    case ResourceMovedAndModified(_, _, _, resource)               => invalidateDerived(resource.resourceId) >> db.upsertResource(resource)
    case _                                                         => IO.unit
  }

  override def processEvent(event: ResourceEvent): IO[Unit] =
    db.transact(s => applyEventToDb(event) >> topic.publish(s, event)) >>
      IO(logger.info(s"[$bucketId] $event"))

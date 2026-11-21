package nl.amony.modules.resources.impl

import java.nio.file.Files
import scala.jdk.CollectionConverters.*
import scala.util.Using

import cats.effect.IO
import scribe.Logging
import skunk.Session

import nl.amony.modules.resources.api.*
import nl.amony.modules.resources.dal.ResourceDatabase
import nl.amony.platform.messagebus.MessageTopic

/** Applies events discovered by a local directory scan to the database and the event topic. */
final class LocalDirectoryEventStore(
  config: LocalDirectoryConfig,
  db: ResourceDatabase,
  topic: MessageTopic[ResourceEvent]
) extends Logging:

  private val bucketId: BucketId = BucketId(config.id)

  /** Deletes cached previews derived from a resource, so changed content is never served stale. */
  private def deleteDerivedFiles(resourceId: ResourceId): IO[Unit] = IO.blocking {
    if Files.isDirectory(config.cachePath) then
      Using(Files.list(config.cachePath)) { stream =>
        stream.iterator().asScala
          .filter { path =>
            val name = path.getFileName.toString
            name.startsWith(resourceId.toString + "_") || name.startsWith(resourceId.toString + "-")
          }
          .foreach(path => Files.deleteIfExists(path))
      }
  }.void.handleErrorWith(e => IO(logger.warn(s"Failed to delete derived files for $resourceId", e)))

  private def applyEventToDb(event: ResourceEvent)(using Option[Session[IO]]): IO[Unit] = event match {
    case ResourceAdded(resource)                                   => db.insertResource(resource)
    case ResourceDeleted(resource)                                 => db.deleteResource(bucketId, resource.resourceId)
    case ResourceMoved(_, id, _, newPath)                          => db.move(bucketId, id, newPath)
    case ResourceFileMetaChanged(_, id, lastModifiedTime, fileKey) => db.getResourceById(bucketId, id).flatMap {
        case Some(resource) =>
          db.upsertResource(resource.copy(timeLastModified = Some(lastModifiedTime), fileKey = fileKey.orElse(resource.fileKey)))
        case None           => IO.unit
      }
    case ResourceModified(resource)                                => deleteDerivedFiles(resource.resourceId) >> db.upsertResource(resource)
    case _                                                         => IO.unit
  }

  def processEvent(event: ResourceEvent): IO[Unit] =
    db.transact(s => applyEventToDb(event) >> topic.publish(s, event)) >>
      IO(logger.info(s"[${config.id}] $event"))

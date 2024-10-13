package nl.amony.service.resources.local

import cats.effect.IO
import nl.amony.lib.eventbus.EventTopic
import nl.amony.lib.files.PathOps
import nl.amony.service.resources.*
import nl.amony.service.resources.ResourceConfig.LocalDirectoryConfig
import nl.amony.service.resources.api.ResourceInfo
import nl.amony.service.resources.api.events.{ResourceEvent, ResourceUserMetaUpdated}
import nl.amony.service.resources.api.operations.ResourceOperation
import nl.amony.service.resources.local.LocalResourceOperations.*
import nl.amony.service.resources.local.db.LocalDirectoryDb
import scribe.Logging
import slick.jdbc.JdbcProfile

import java.nio.file.{Files, Path}
import java.util.concurrent.ConcurrentHashMap

class LocalDirectoryBucket[P <: JdbcProfile](config: LocalDirectoryConfig, db: LocalDirectoryDb[P], topic: EventTopic[ResourceEvent]) extends ResourceBucket with Logging {

  private val resourceStore = new ConcurrentHashMap[LocalResourceOp, IO[Path]]()

  Files.createDirectories(config.writePath)

  private def getResourceInfo(resourceId: String): IO[Option[ResourceInfo]] = db.getByHash(config.id, resourceId)
    
  override def getOrCreate(resourceId: String, operation: ResourceOperation, tags: Set[String]): IO[Option[Resource]] =
    getResourceInfo(resourceId).flatMap:
      case None           => IO.pure(None)
      case Some(fileInfo) => derivedResource(fileInfo, LocalResourceOp(resourceId, operation))

  private def derivedResource(inputResource: ResourceInfo, operation: LocalResourceOp): IO[Option[LocalFile]] =
    // this is to prevent 2 or more requests for the same resource to trigger the operation multiple times
    resourceStore
      .compute(operation, (_, value) => { 
        getOrCreateResource(config.resourcePath.resolve(inputResource.path), config.writePath, operation) 
      }).map(path => Resource.fromPath(path, inputResource))

  override def getResource(resourceId: String): IO[Option[Resource]] =
    getResourceInfo(resourceId).flatMap:
      case None       => IO.pure(None)
      case Some(info) =>
        val path = config.resourcePath.resolve(info.path)
        IO.pure(Resource.fromPath(path, info))

  override def getChildren(resourceId: String, tags: Set[String]): IO[Seq[ResourceInfo]] =
    db.getChildren(config.id, resourceId, tags)

  override def uploadResource(fileName: String, source: fs2.Stream[IO, Byte]): IO[ResourceInfo] = ???

  override def deleteResource(resourceId: String): IO[Unit] =
    getResourceInfo(resourceId).flatMap:
      case None       => IO.pure(())
      case Some(info) =>
        val path = config.resourcePath.resolve(info.path)
        db.deleteResource(config.id, resourceId, () => IO(path.deleteIfExists()))

  override def updateUserMeta(resourceId: String, title: Option[String], description: Option[String], tags: List[String]): IO[Unit] =
    db.updateUserMeta(
      config.id, resourceId, title, description, tags, 
      IO { topic.publish(ResourceUserMetaUpdated(config.id, resourceId, title, description, tags)) }
    )
}

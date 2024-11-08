package nl.amony.service.resources.local

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import nl.amony.lib.eventbus.EventTopic
import nl.amony.lib.files.PathOps
import nl.amony.service.resources.*
import nl.amony.service.resources.ResourceConfig.LocalDirectoryConfig
import nl.amony.service.resources.api.ResourceInfo
import nl.amony.service.resources.api.events.{ResourceEvent, ResourceUpdated }
import nl.amony.service.resources.api.operations.ResourceOperation
import nl.amony.service.resources.local.LocalResourceOperations.*
import nl.amony.service.resources.local.db.ResourcesDb
import nl.amony.service.resources.local.scanner.LocalDirectoryScanner
import scribe.Logging
import slick.jdbc.JdbcProfile

import java.nio.file.{Files, Path}
import java.util.concurrent.ConcurrentHashMap

class LocalDirectoryBucket[P <: JdbcProfile](config: LocalDirectoryConfig, db: ResourcesDb[P], topic: EventTopic[ResourceEvent])(using runtime: IORuntime) extends ResourceBucket with Logging {

  private val resourceStore = new ConcurrentHashMap[LocalResourceOp, IO[Path]]()
  private val scanner = LocalDirectoryScanner(config)
  
  Files.createDirectories(config.cachePath)

  private def getResourceInfo(resourceId: String): IO[Option[ResourceInfo]] = db.getByHash(config.id, resourceId)

  override def getOrCreate(resourceId: String, operation: ResourceOperation): IO[Option[Resource]] =
    getResourceInfo(resourceId).flatMap:
      case None           => IO.pure(None)
      case Some(fileInfo) => derivedResource(fileInfo, LocalResourceOp(resourceId, operation))

  private def derivedResource(inputResource: ResourceInfo, operation: LocalResourceOp): IO[Option[LocalFile]] = {
    val outputFile = config.cachePath.resolve(operation.outputFilename)

    if (Files.exists(outputFile))
      IO.pure(Resource.fromPathMaybe(outputFile, inputResource))
    else {
      /**
       * This is not ideal, there is still a small time window in which the operation can be triggered multiple times, although it is very unlikely to happen
       * TODO Create a full proof solution using a MapRef from cats
       */
      resourceStore
        .compute(operation, (_, value) => { createResource(config.resourcePath.resolve(inputResource.path), inputResource, config.cachePath, operation) })
        .map(path => Resource.fromPathMaybe(path, inputResource))
        .flatTap { _ => IO(resourceStore.remove(operation)) } // removes the operation from the map to prevent memory leak, leaves a small gap
    }
  }

  override def getResource(resourceId: String): IO[Option[Resource]] =
    getResourceInfo(resourceId).flatMap:
      case None       =>
        IO.pure(None)
      case Some(info) =>
        val path = config.resourcePath.resolve(info.path)
        if(!path.exists()) {
          logger.warn(s"Resource '$resourceId' was found in the database but no file exists: $path")
          IO.pure(None)
        } else {
          IO.pure(Some(Resource.fromPath(path, info)))
        }

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
      resource => topic.publish(ResourceUpdated(resource))
    )

  override def updateThumbnailTimestamp(resourceId: String, timestamp: Long): IO[Unit] =
    db.updateThumbnailTimestamp(config.id, resourceId, timestamp,
      resource => topic.publish(ResourceUpdated(resource))
    )
}

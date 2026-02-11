package nl.amony.modules.resources.local

import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import cats.implicits.*
import org.typelevel.otel4s.metrics.Meter
import scribe.Logging
import skunk.Session

import nl.amony.lib.files.*
import nl.amony.lib.messagebus.EventTopic
import nl.amony.modules.resources.*
import nl.amony.modules.resources.ResourceConfig.LocalDirectoryConfig
import nl.amony.modules.resources.api.*
import nl.amony.modules.resources.dal.ResourceDatabase

object LocalDirectoryBucket:

  def resource(
    config: LocalDirectoryConfig,
    pool: cats.effect.Resource[IO, Session[IO]],
    topic: EventTopic[ResourceEvent]
  )(
    using runtime: IORuntime,
    meter: Meter[IO]
  ): cats.effect.Resource[IO, LocalDirectoryBucket] = {
    cats.effect.Resource.make {
      IO {
        val bucket = LocalDirectoryBucket(config, ResourceDatabase(pool), topic)
        bucket.sync().unsafeRunAsync(_ => ())
        bucket
      }
    }(_ => IO.unit)
  }

class LocalDirectoryBucket(
  config: LocalDirectoryConfig,
  db: ResourceDatabase,
  topic: EventTopic[ResourceEvent]
)(using runtime: IORuntime, meter: Meter[IO])
    extends LocalDirectoryBase(config, db, topic), LocalResourceOperations, ResourceBucket, LocalResourceSyncer, UploadResource, Logging {

  private def getResourceInfo(resourceId: String): IO[Option[ResourceInfo]] = db.getById(config.id, resourceId)

  override def id: String = config.id

  def reScanAllMetadata(): IO[Unit] = getAllResources.evalMap {
    resource =>
      val resourcePath = config.resourcePath.resolve(resource.path)
      meta.apply(resourcePath).flatMap:
        case None                      =>
          logger.warn(s"Failed to scan metadata for $resourcePath")
          IO.unit
        case Some((contentType, meta)) =>
          logger.info(s"Updating metadata for $resourcePath")
          val updated = resource.copy(
            contentType = Some(contentType),
            contentMeta = Some(meta)
          )

          db.upsert(updated) >> topic.publish(ResourceUpdated(updated))
  }.compile.drain

  def updateFileSystemMetaData(): IO[Unit] = getAllResources.evalMap {
    resource =>
      val resourcePath = config.resourcePath.resolve(resource.path)
      val attrs        = Files.readAttributes(resourcePath, classOf[BasicFileAttributes])
      val updated      = resource.copy(size = attrs.size(), timeLastModified = Some(attrs.lastModifiedTime().toMillis))

      if updated.size != resource.size || updated.timeLastModified != resource.timeLastModified then
        logger.info(s"File system metadata changed for $resourcePath")
        db.upsert(updated) >> topic.publish(ResourceUpdated(updated))
      else IO.unit
  }.compile.drain

  def reComputeHashes(): IO[Unit] = getAllResources.evalMap { resource =>
    val file = config.resourcePath.resolve(resource.path)
    config.hashingAlgorithm.createHash(file).flatMap: hash =>
      val oldResourceId = resource.resourceId
      val updated       = resource.copy(hash = Some(hash))
      if oldResourceId != hash then
        logger.info(s"Updating hash for $file from $oldResourceId to $hash")
        db.deleteResource(config.id, resource.resourceId) >> topic.publish(ResourceDeleted(oldResourceId)) >> db.insertResource(updated) >>
          topic.publish(ResourceUpdated(updated))
      else IO.unit
  }.compile.drain

  override def getOrCreate(resourceId: ResourceId, operation: ResourceOperation): IO[Option[ResourceContent]] =
    getResourceInfo(resourceId).flatMap:
      case None       => IO.pure(None)
      case Some(info) => derivedResource(info, operation)

  override def getResource(resourceId: ResourceId): IO[Option[Resource]] =
    getResourceInfo(resourceId).map:
      case None       => None
      case Some(info) =>
        val path = config.resourcePath.resolve(info.path)
        if !path.exists() then {
          logger.warn(s"Resource '$resourceId' was found in the database but no file exists: $path")
          None
        } else { Some(Resource(info, ResourceContent.fromPath(path, info.contentType))) }

  override def deleteResource(resourceId: ResourceId): IO[Unit] =
    getResourceInfo(resourceId).flatMap:
      case None       => IO.pure(())
      case Some(info) =>
        val path = config.resourcePath.resolve(info.path)
        db.deleteResource(config.id, resourceId) >> IO(path.deleteIfExists()) >> topic.publish(ResourceDeleted(resourceId))

  override def updateUserMeta(resourceId: ResourceId, title: Option[String], description: Option[String], tags: List[String]): IO[Unit] =
    db.updateUserMeta(config.id, resourceId, title, description, tags)
      .flatMap(_.map(updated => topic.publish(ResourceUpdated(updated))).getOrElse(IO.unit))

  override def modifyTags(resourceIds: Set[ResourceId], tagsToAdd: Set[String], tagsToRemove: Set[String]): IO[Unit] = {
    def modifyTagsSingle(resourceId: ResourceId, tagsToAdd: Set[String], tagsToRemove: Set[String]): IO[Unit] =
      db.modifyTags(config.id, resourceId, tagsToAdd, tagsToRemove).flatMap:
        case None          => IO.unit
        case Some(updated) => topic.publish(ResourceUpdated(updated))

    resourceIds.map(id => modifyTagsSingle(id, tagsToAdd, tagsToRemove)).toList.sequence.as(())
  }

  override def updateThumbnailTimestamp(resourceId: ResourceId, timestamp: Int): IO[Unit] =
    db.updateThumbnailTimestamp(config.id, resourceId, timestamp)
      .flatMap(_.map(updated => topic.publish(ResourceUpdated(updated))).getOrElse(IO.unit))

  def importBackup(resources: fs2.Stream[IO, ResourceInfo]): IO[Unit] =
    db.truncateTables() >> resources.map(r => r.copy(resourceId = config.generateId(), title = None))
      .evalMap(resource => IO(logger.info(s"Inserting resource: ${resource.resourceId}")) >> db.insertResource(resource)).compile.drain

  override def getAllResources: fs2.Stream[IO, ResourceInfo] =
    db.getStream(config.id)
}

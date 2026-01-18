package nl.amony.modules.resources.local

import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{Files, Path as JPath}
import java.util.concurrent.ConcurrentHashMap

import cats.effect.std.MapRef
import cats.effect.unsafe.IORuntime
import cats.effect.{Deferred, IO}
import cats.implicits.*
import scribe.Logging

import nl.amony.lib.files.*
import nl.amony.lib.messagebus.EventTopic
import nl.amony.modules.resources.*
import nl.amony.modules.resources.ResourceConfig.LocalDirectoryConfig
import nl.amony.modules.resources.api.{ResourceId, *}
import nl.amony.modules.resources.database.ResourceDatabase
import nl.amony.modules.resources.local.LocalResourceOperations.*

trait LocalDirectoryDependencies(val config: LocalDirectoryConfig, val db: ResourceDatabase, val topic: EventTopic[ResourceEvent])

object LocalDirectoryBucket:

  def resource(config: LocalDirectoryConfig, db: ResourceDatabase, topic: EventTopic[ResourceEvent])(
    using runtime: IORuntime
  ): cats.effect.Resource[IO, LocalDirectoryBucket] = {
    cats.effect.Resource.make {
      IO {
        val bucket = LocalDirectoryBucket(config, db, topic)
        Files.createDirectories(config.cachePath)
        bucket.sync().unsafeRunAsync(_ => ())
        bucket
      }
    }(_ => IO.unit)
  }

class LocalDirectoryBucket(config: LocalDirectoryConfig, db: ResourceDatabase, topic: EventTopic[ResourceEvent])(using runtime: IORuntime)
    extends LocalDirectoryDependencies(config, db, topic), ResourceBucket, LocalResourceSyncer, UploadResource, Logging {

  private val runningOperations = new ConcurrentHashMap[LocalResourceOp, IO[JPath]]()

  private val mapRefOps: MapRef[IO, LocalResourceOp, Option[Deferred[IO, Either[Throwable, JPath]]]] =
    MapRef.fromConcurrentHashMap[IO, LocalResourceOp, Deferred[IO, Either[Throwable, JPath]]](
      new ConcurrentHashMap[LocalResourceOp, Deferred[IO, Either[Throwable, JPath]]]()
    )

  private def getResourceInfo(resourceId: String): IO[Option[ResourceInfo]] = db.getById(config.id, resourceId)

  override def id: String = config.id

  def reScanAllMetadata(): IO[Unit] = getAllResources.evalMap {
    resource =>
      val resourcePath = config.resourcePath.resolve(resource.path)
      LocalResourceMeta(resourcePath).flatMap:
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

  def reComputeHashes(): IO[Unit] = getAllResources.evalMap {
    resource =>
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

  override def getOrCreate(resourceId: ResourceId, operation: ResourceOperation): IO[Option[Resource]] = getResourceInfo(resourceId).flatMap:
    case None           => IO.pure(None)
    case Some(fileInfo) => cachedResourceOperation(fileInfo, LocalResourceOp(resourceId, operation))

  private[local] def cachedResourceOperation(inputResource: ResourceInfo, operation: LocalResourceOp): IO[Option[LocalFile]] = {
    val outputFile          = config.cachePath.resolve(operation.outputFilename)
    val derivedResourceInfo = inputResource
      .copy(path = outputFile.getFileName.toString, contentType = Some(operation.contentType), contentMeta = None)

    if Files.exists(outputFile) then IO.pure(Resource.fromPathMaybe(outputFile, derivedResourceInfo))
    else {

      def runOperation(deferred: Deferred[IO, Either[Throwable, JPath]]): IO[JPath] =
        createResource(config.resourcePath.resolve(inputResource.path), inputResource, config.cachePath, operation)
          .attempt
          .flatTap(result => deferred.complete(result))
          .flatTap(_ => mapRefOps(operation).set(None))
          .rethrow

      Deferred[IO, Either[Throwable, JPath]].flatMap {
        newDeferred =>
          mapRefOps(operation).modify {
            case Some(existing) => (Some(existing), existing.get.rethrow)
            case None           => (Some(newDeferred), runOperation(newDeferred))
          }
      }.flatten.map(path => Resource.fromPathMaybe(path, derivedResourceInfo))
    }
  }

  override def getResource(resourceId: ResourceId): IO[Option[Resource]] =
    getResourceInfo(resourceId).map:
      case None       => None
      case Some(info) =>
        val path = config.resourcePath.resolve(info.path)
        if !path.exists() then {
          logger.warn(s"Resource '$resourceId' was found in the database but no file exists: $path")
          None
        } else { Some(Resource.fromPath(path, info)) }

  override def deleteResource(resourceId: ResourceId): IO[Unit] =
    getResourceInfo(resourceId).flatMap:
      case None       => IO.pure(())
      case Some(info) =>
        val path = config.resourcePath.resolve(info.path)
        db.deleteResource(config.id, resourceId) >> IO(path.deleteIfExists())

  override def updateUserMeta(resourceId: ResourceId, title: Option[String], description: Option[String], tags: List[String]): IO[Unit] =
    db.updateUserMeta(config.id, resourceId, title, description, tags)
      .flatMap(_.map(updated => topic.publish(ResourceUpdated(updated))).getOrElse(IO.unit))

  override def modifyTags(resourceIds: Set[ResourceId], tagsToAdd: Set[String], tagsToRemove: Set[String]): IO[Unit] = {
    def modifyTagsSingle(resourceId: ResourceId, tagsToAdd: Set[String], tagsToRemove: Set[String]): IO[Unit] =
      db.modifyTags(config.id, resourceId, tagsToAdd, tagsToRemove).flatMap:
        case None          => IO.unit
        case Some(updated) => topic.publish(ResourceUpdated(updated))

    resourceIds.map(id => modifyTagsSingle(id, tagsToAdd, tagsToRemove)).toList.sequence.as(None)
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

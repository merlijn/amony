package nl.amony.modules.resources.local

import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{Files, StandardCopyOption}

import cats.effect.IO
import cats.implicits.*
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import scribe.Logging

import nl.amony.modules.auth.api.Role
import nl.amony.modules.resources.api.*
import nl.amony.modules.resources.dal.ResourceDatabase
import nl.amony.platform.files.*
import nl.amony.platform.messagebus.MessageTopic

class LocalDirectoryBucket(
  config: LocalDirectoryConfig,
  parallelFactor: Int,
  db: ResourceDatabase,
  topic: MessageTopic[ResourceEvent],
  formats: ThumbnailFormats,
  resolutions: ThumbnailResolutions
)(using meter: Meter[IO], tracer: Tracer[IO])
    extends LocalDirectoryBase(config, parallelFactor, db, topic, formats, resolutions), LocalResourceOperations, ResourceBucket,
      LocalResourceSyncer,
      UploadResource, Logging {

  private def getResourceInfo(resourceId: ResourceId): IO[Option[ResourceInfo]] = db.getResourceById(id, resourceId)

  override def id: BucketId = BucketId(config.id)

  override def requiredRole: Option[Role] = config.requiredRole

  def reScanAllMetadata(): IO[Unit] = getAllResources.evalMap { resource =>
    val resourcePath = config.resourcePath.resolve(resource.path)
    contentMetaForPath(resourcePath).attempt.flatMap {
      case Left(e: ContentUnreadable) => IO(logger.error(s"Skipping metadata rescan for $resourcePath", e))
      case Left(e)                    => IO.raiseError(e)
      case Right(meta)                =>
        logger.info(s"Updating metadata for $resourcePath")
        val updated = resource.copy(contentMeta = resource.contentMeta.updatedWith(meta))
        db.transact(s => db.upsertResourceWith(s, updated) >> topic.publish(s, ResourceUpdated(updated)))
    }
  }.compile.drain

  /** Remuxes a non-streamable resource in place so it can be streamed progressively, then persists the new state. */
  def fixStreamability(resourceId: ResourceId): IO[Unit] =
    getResourceInfo(resourceId).flatMap {
      case Some(info) if info.streamable.contains(false) =>
        val source = config.resourcePath.resolve(info.path)
        VideoContainer.fromContentType(info.contentType.getOrElse("")) match
          case Some(container) =>
            logger.info(s"Normalizing '${info.path}' for streaming")
            val temp = config.cachePath.resolve(s"${info.resourceId}-normalize.${container.extension}")
            (for
              _          <- IO(Files.createDirectories(config.cachePath))
              out        <- addFastStart(source, container, Some(temp))
              _          <- IO(Files.move(out, source, StandardCopyOption.REPLACE_EXISTING))
              attrs      <- IO(Files.readAttributes(source, classOf[BasicFileAttributes]))
              hash       <- config.hashingAlgorithm.createHash(source)
              streamable <- streamableOf(source, info.contentType)
              updated     = info.copy(
                              size             = attrs.size(),
                              partialHash      = Some(hash),
                              fileKey          = Option(attrs.fileKey()).map(_.toString),
                              timeLastModified = Some(attrs.lastModifiedTime().toMillis),
                              contentMeta      = info.contentMeta.copy(streamable = streamable)
                            )
              _          <- db.transact(s => db.upsertResourceWith(s, updated) >> topic.publish(s, ResourceUpdated(updated)))
            yield ())
              .handleErrorWith(error => IO(logger.error(s"Failed to normalize '${info.path}'", error)))
              .guarantee(IO.blocking(Files.deleteIfExists(temp)).void)
          case None            =>
            IO(logger.warn(s"Cannot normalize '${info.path}': unsupported container '${info.contentType.getOrElse("unknown")}'"))
      case _                                             => IO.unit
    }

  def updateFileSystemMetaData(): IO[Unit] = getAllResources.evalMap {
    resource =>
      val resourcePath = config.resourcePath.resolve(resource.path)
      val attrs        = Files.readAttributes(resourcePath, classOf[BasicFileAttributes])
      val updated      = resource.copy(
        size             = attrs.size(),
        fileKey          = Option(attrs.fileKey()).map(_.toString),
        timeLastModified = Some(attrs.lastModifiedTime().toMillis)
      )

      if updated.size != resource.size || updated.timeLastModified != resource.timeLastModified || updated.fileKey != resource.fileKey then
        logger.info(s"File system metadata changed for $resourcePath")
        db.transact(s => db.upsertResourceWith(s, updated) >> topic.publish(s, ResourceUpdated(updated)))
      else IO.unit
  }.compile.drain

  def reComputePartialHashes(): IO[Unit] = getAllResources.evalMap { resource =>
    val file = config.resourcePath.resolve(resource.path)
    config.hashingAlgorithm.createHash(file).flatMap: partialHash =>
      val updated = resource.copy(partialHash = Some(partialHash))
      if resource.partialHash.contains(partialHash) then IO.unit
      else
        logger.info(s"Updating partialHash for $file to $partialHash")
        db.transact(s => db.upsertResourceWith(s, updated) >> topic.publish(s, ResourceUpdated(updated)))
  }.compile.drain

  override def getOrCreate(resourceId: ResourceId, operation: ResourceOperation): IO[Option[ResourceContent]] =
    getResourceInfo(resourceId).flatMap:
      case None       => IO.pure(None)
      case Some(info) => derivedResource(info, operation)

  def generateAllPreviews(): IO[Unit] =
    getAllResources
      .flatMap(info => fs2.Stream.emits(previewOperations(info).map(info -> _)))
      .parEvalMap(parallelFactor) { case (info, operation) => runPreviewOperation(info, operation) }
      .compile.drain

  def generatePreviewsIfEnabled(info: ResourceInfo): IO[Unit] =
    if config.generatePreviewsOnAdd then generatePreviews(info)
    else IO.unit

  override def getResource(resourceId: ResourceId): IO[Option[Resource]] =
    getResourceInfo(resourceId).map:
      case None       => None
      case Some(info) =>
        val path = config.resourcePath.resolve(info.path)
        if !path.exists() then {
          logger.warn(s"Resource '$resourceId' was found in the database but no file exists: $path")
          None
        } else { Some(Resource(info, ResourceContent.fromPath(path, info.contentType, originalETag(info)))) }

  override def deleteResource(resourceId: ResourceId): IO[Unit] =
    getResourceInfo(resourceId).flatMap:
      case None       => IO.pure(())
      case Some(info) =>
        val path = config.resourcePath.resolve(info.path)
        db.transact(s => db.deleteResourceWith(s, id, resourceId) >> topic.publish(s, ResourceDeleted(resourceId))) >> IO(path.deleteIfExists())

  override def updateUserMeta(resourceId: ResourceId, title: Option[String], description: Option[String], tags: List[String]): IO[Unit] =
    db.transact: s =>
      // User-supplied labels always carry the user owner; other owners are preserved.
      db.updateUserMetaWith(s, id, resourceId, title, description, TagOwner.User, tags)
        .flatMap(_.map(updated => topic.publish(s, ResourceUpdated(updated))).getOrElse(IO.unit))

  /** Maximum resources updated in a single transaction: atomic per batch without holding locks for the whole selection. */
  private val tagBulkBatchSize = 100

  override def updateResourceTags(resourceIds: Set[ResourceId], tagsToAdd: Set[Tag], tagsToRemove: Set[Tag]): IO[Unit] =
    resourceIds.toList.grouped(tagBulkBatchSize).toList.traverse_ : batch =>
      db.transact: s =>
        batch.traverse_ : resourceId =>
          db.updateResourceTagsWith(s, id, resourceId, tagsToAdd, tagsToRemove).flatMap:
            case None          => IO.unit
            case Some(updated) => topic.publish(s, ResourceUpdated(updated))

  override def updateThumbnailTimestamp(resourceId: ResourceId, timestamp: Int): IO[Unit] =
    db.transact: s =>
      db.updateThumbnailTimestampWith(s, id, resourceId, timestamp)
        .flatMap(_.map(updated => topic.publish(s, ResourceUpdated(updated))).getOrElse(IO.unit))

  def importBackup(resources: fs2.Stream[IO, ResourceInfo]): IO[Unit] =
    db.truncateTables() >> resources.map(r => r.copy(resourceId = config.generateId(), title = None))
      .evalMap(resource => IO(logger.info(s"Inserting resource: ${resource.resourceId}")) >> db.insertResource(resource)).compile.drain

  override def getAllResources: fs2.Stream[IO, ResourceInfo] =
    db.getStream(id)
}

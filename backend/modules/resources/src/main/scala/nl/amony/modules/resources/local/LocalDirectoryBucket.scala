package nl.amony.modules.resources.local

import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

import cats.effect.IO
import cats.implicits.*
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import scribe.Logging
import skunk.Session

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
  ops: FileOperations
)(using meter: Meter[IO], tracer: Tracer[IO])
    extends LocalDirectoryBase(config, parallelFactor, db, topic, ops),
      ResourceBucket,
      LocalResourceSyncer,
      UploadOperations,
      Logging {

  private def getResourceInfo(resourceId: ResourceId): IO[Option[ResourceInfo]] = db.getResourceById(id, resourceId)

  override def id: BucketId = BucketId(config.id)

  override def requiredRole: Option[Role] = config.requiredRole

  def reScanAllMetadata(): IO[Unit] = getAllResources.evalMap { resource =>
    val resourcePath = config.resourcePath.resolve(resource.path)
    ops.contentMetaForPath(resourcePath).attempt.flatMap {
      case Left(e: ContentUnreadable) => IO(logger.error(s"Skipping metadata rescan for $resourcePath", e))
      case Left(e)                    => IO.raiseError(e)
      case Right(meta)                =>
        logger.info(s"Updating metadata for $resourcePath")
        val updated = resource.copy(contentMeta = resource.contentMeta.updatedWith(meta))
        db.transact(s => db.upsertResource(updated) >> topic.publish(s, ResourceModified(updated)))
    }
  }.compile.drain

  /**
   * Cheap validation for the request: reports whether a remux makes sense without touching the file. The remux itself
   * runs in the command consumer.
   */
  private[local] def checkStreamabilityFix(resourceId: ResourceId): IO[StreamabilityFixCheck] =
    getResourceInfo(resourceId).map:
      case None                                                 => StreamabilityFixCheck.Missing
      case Some(info) if !info.streamable.contains(false)       =>
        StreamabilityFixCheck.Rejected("Resource is already streamable or its streamability is unknown")
      case Some(info) if AddFastStart.forResource(info).isEmpty =>
        StreamabilityFixCheck.Rejected("The container format cannot be remuxed for streaming")
      case Some(_)                                              => StreamabilityFixCheck.Fixable

  /** Remuxes to the bucket's temp directory without touching the live file. Returns None when nothing is needed. */
  private[local] def remuxForStreaming(resourceId: ResourceId): IO[Option[String]] =
    getResourceInfo(resourceId).flatMap:
      case None                                           => IO.raiseError(new StreamabilityFixFailed(s"$resourceId", "resource no longer exists"))
      case Some(info) if !info.streamable.contains(false) => IO.pure(None)
      case Some(info)                                     => ops.remuxForStreaming(info).map(Some(_))

  /**
   * Commits a remux: replaces the live file, refreshes its metadata and publishes ResourceModified, all in the caller's
   * transaction. That way the command's success and the resource update commit together.
   */
  private[local] def commitStreamabilityFix(session: Session[IO], resourceId: ResourceId, tempFile: Option[String]): IO[Unit] =
    db.getResourceById(id, resourceId)(using Some(session)).flatMap:
      case None       => IO.raiseError(new StreamabilityFixFailed(s"$resourceId", "resource no longer exists"))
      case Some(info) =>
        ops.commitStreamability(info, tempFile).flatMap { updated =>
          db.upsertResource(updated)(using Some(session)) >> topic.publish(session, ResourceModified(updated))
        }

  def reComputePartialHashes(): IO[Unit] = getAllResources.evalMap { resource =>
    val file = config.resourcePath.resolve(resource.path)
    config.hashingAlgorithm.createHash(file).flatMap: partialHash =>
      val updated = resource.copy(partialHash = Some(partialHash))
      if resource.partialHash.contains(partialHash) then IO.unit
      else
        logger.info(s"Updating partialHash for $file to $partialHash")
        db.transact(s => db.upsertResource(updated) >> topic.publish(s, ResourceModified(updated)))
  }.compile.drain

  override def getOrCreate(resourceId: ResourceId, operation: ResourceOperation): IO[Option[ResourceContent]] =
    getResourceInfo(resourceId).flatMap:
      case None       => IO.pure(None)
      case Some(info) => ops.derivedResource(info, operation)

  def generateAllPreviews(): IO[Unit] =
    getAllResources
      .flatMap(info => fs2.Stream.emits(ops.previewOperations(info).map(info -> _)))
      .parEvalMap(parallelFactor) { case (info, operation) => ops.runPreviewOperation(info, operation) }
      .compile.drain

  def generatePreviewsIfEnabled(info: ResourceInfo): IO[Unit] =
    if config.generatePreviewsOnAdd then ops.generatePreviews(info)
    else IO.unit

  def deleteFile(info: ResourceInfo): IO[Unit] =
    val path = config.resourcePath.resolve(info.path)
    IO.blocking(path.deleteIfExists()).void

  override def getResource(resourceId: ResourceId): IO[Option[Resource]] =
    getResourceInfo(resourceId).map:
      case None       => None
      case Some(info) =>
        val path = config.resourcePath.resolve(info.path)
        if !path.exists() then {
          logger.warn(s"Resource '$resourceId' was found in the database but no file exists: $path")
          None
        } else { Some(Resource(info, ResourceContent.fromPath(path, info.contentType, ops.originalETag(info)))) }

  override def deleteResource(resourceId: ResourceId): IO[Unit] =
    getResourceInfo(resourceId).flatMap:
      case None       => IO.pure(())
      case Some(info) =>
        db.transact(s => db.deleteResource(id, resourceId) >> topic.publish(s, ResourceDeleted(info)))

  override def updateUserMeta(resourceId: ResourceId, title: Option[String], description: Option[String], tags: List[String]): IO[Unit] =
    db.transact: s =>
      db.getResourceByIdForUpdate(id, resourceId).flatMap:
        case None           => IO.unit
        case Some(existing) =>
          val existingUserTags = existing.tags.filter(_.owner == TagOwner.User)
          val newUserTags      = tags.toSet.map(Tag.user)
          val tagsAdded        = newUserTags -- existingUserTags
          val tagsRemoved      = existingUserTags -- newUserTags
          // User-supplied labels always carry the user owner; other owners are preserved.
          db.updateUserMeta(id, resourceId, title, description, TagOwner.User, tags)
            .flatMap(
              _.map(updated =>
                topic.publish(
                  s,
                  ResourceUserMetaUpdated(
                    resource           = updated,
                    updatedDescription = description,
                    updatedTitle       = title,
                    tagsAdded          = tagsAdded,
                    tagsRemoved        = tagsRemoved
                  )
                )
              ).getOrElse(IO.unit)
            )

  /** Maximum resources updated in a single transaction: atomic per batch without holding locks for the whole selection. */
  private val tagBulkBatchSize = 100

  override def updateResourceTags(resourceIds: Set[ResourceId], tagsToAdd: Set[Tag], tagsToRemove: Set[Tag]): IO[Unit] =
    resourceIds.toList.grouped(tagBulkBatchSize).toList.traverse_ : batch =>
      db.transact: s =>
        batch.traverse_ : resourceId =>
          db.updateResourceTags(id, resourceId, tagsToAdd, tagsToRemove).flatMap:
            case None          => IO.unit
            case Some(updated) =>
              topic.publish(
                s,
                ResourceUserMetaUpdated(
                  resource           = updated,
                  updatedDescription = None,
                  updatedTitle       = None,
                  tagsAdded          = tagsToAdd,
                  tagsRemoved        = tagsToRemove
                )
              )

  override def updateThumbnailTimestamp(resourceId: ResourceId, timestamp: Int): IO[Unit] =
    db.transact: s =>
      db.updateThumbnailTimestamp(id, resourceId, timestamp)
        .flatMap(_.map(updated => topic.publish(s, ResourceThumbnailUpdated(timestamp, updated))).getOrElse(IO.unit))

  def importBackup(resources: fs2.Stream[IO, ResourceInfo]): IO[Unit] =
    db.truncateTables() >> resources.map(r => r.copy(resourceId = config.generateId(), title = None))
      .evalMap(resource => IO(logger.info(s"Inserting resource: ${resource.resourceId}")) >> db.insertResource(resource)).compile.drain

  override def getAllResources: fs2.Stream[IO, ResourceInfo] =
    db.getStream(id)
}

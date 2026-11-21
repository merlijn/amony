package nl.amony.modules.resources.impl

import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import scala.concurrent.duration.*

import cats.effect.IO
import cats.implicits.*
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import scribe.Logging
import skunk.Session

import nl.amony.modules.auth.api.{Role, UserId}
import nl.amony.modules.resources.api.*
import nl.amony.modules.resources.dal.ResourceDatabase
import nl.amony.platform.files.*
import nl.amony.platform.messagebus.{JobBus, JobState, MessageTopic}

class DirectoryBucketImpl(
  config: LocalDirectoryConfig,
  parallelFactor: Int,
  db: ResourceDatabase,
  topic: MessageTopic[ResourceEvent],
  ops: FileOperations,
  store: ResourceStore,
  jobBus: JobBus[ResourceJob] = null,
  jobTimeout: FiniteDuration  = 30.minutes
)(using meter: Meter[IO], tracer: Tracer[IO])
    extends LocalDirectoryBase(config, store, parallelFactor, db, topic, ops),
      ResourceBucket,
      LocalResourceSyncer,
      UploadOperations,
      Logging {

  override def getResourceInfo(resourceId: ResourceId): IO[Option[ResourceInfo]] = db.getResourceById(id, resourceId)

  override def id: BucketId = BucketId(config.id)

  override def requiredRole: Option[Role] = config.requiredRole

  override def getResourceContent(resourceId: ResourceId): IO[Option[ResourceContent]] =
    getResourceInfo(resourceId).flatMap:
      case None       => IO.pure(None)
      case Some(info) => store.content(info, ops.originalETag(info))

  override def getAllResources: fs2.Stream[IO, ResourceInfo] =
    db.getStream(id)

  override def reScanAllMetadata(): IO[Unit] = getAllResources.evalMap { resource =>
    val resourcePath = config.resourcePath.resolve(resource.path)
    ops.contentMetaForPath(resourcePath).flatMap {
      case Left(e)     => IO(logger.error(s"Skipping metadata rescan for $resourcePath", e.cause))
      case Right(meta) =>
        logger.info(s"Updating metadata for $resourcePath")
        val updated = resource.copy(contentMeta = resource.contentMeta.updatedWith(meta))
        db.transact(s => db.upsertResource(updated) >> topic.publish(s, ResourceModified(updated)))
    }
  }.compile.drain

  override def fixStreamability(resourceId: ResourceId): IO[Either[ResourceJobError, Unit]] =
    checkStreamabilityFix(resourceId).flatMap:
      case Left(error) => IO.pure(Left(error))
      case Right(())   =>
        val job = FixStreamability(id, resourceId)
        for
          jobId  <- jobBus.submit(job.key, job)
          status <- jobBus.awaitCompletion(jobId, jobTimeout)
        yield status.state match
          case JobState.Succeeded => Right(())
          case _                  => Left(ResourceJobError.Rejected(status.lastError.getOrElse("Failed to fix streamability")))

  override def uploadResource(userId: UserId, fileName: String, source: fs2.Stream[IO, Byte]): IO[Either[UploadError, ResourceInfo]] =
    stageUpload(fileName, source).flatMap:
      case Left(error)   => IO.pure(Left(error))
      case Right(staged) =>
        val job    = UploadResource(id, staged.resourceId, userId, staged.fileName, staged.tempFile)
        val submit = jobBus
          .submit(job.key, job)
          .handleErrorWith(error => discardUpload(staged.tempFile) >> IO.raiseError(error))
        for
          jobId  <- submit
          status <- jobBus.awaitCompletion(jobId, jobTimeout)
          result <- status.state match
                      case JobState.Succeeded =>
                        getResourceInfo(staged.resourceId).map {
                          case Some(info) => Right(info)
                          case None       => Left(UploadError.StorageError("Uploaded resource was not found after completion"))
                        }
                      case _                  =>
                        IO.pure(Left(UploadError.StorageError(status.lastError.getOrElse("Failed to store the uploaded resource"))))
        yield result

  /**
   * Cheap validation for the request: reports whether a remux makes sense without touching the file. The remux itself
   * runs in the job consumer.
   */
  private def checkStreamabilityFix(resourceId: ResourceId): IO[Either[ResourceJobError, Unit]] =
    getResourceInfo(resourceId).map:
      case None                                                 => Left(ResourceJobError.NotFound)
      case Some(info) if !info.streamable.contains(false)       =>
        Left(ResourceJobError.Rejected("Resource is already streamable or its streamability is unknown"))
      case Some(info) if AddFastStart.forResource(info).isEmpty =>
        Left(ResourceJobError.Rejected("The container format cannot be remuxed for streaming"))
      case Some(_)                                              => Right(())

  /** Remuxes to the bucket's temp directory without touching the live file. Returns None when nothing is needed. */
  override def remuxForStreaming(resourceId: ResourceId): IO[Either[StreamabilityError, Option[String]]] =
    getResourceInfo(resourceId).flatMap:
      case None                                           => IO.pure(Left(StreamabilityError.ResourceNotFound(resourceId)))
      case Some(info) if !info.streamable.contains(false) => IO.pure(Right(None))
      case Some(info)                                     => ops.remuxForStreaming(info).map(_.map(Some(_)))

  /**
   * Commits a remux: replaces the live file, refreshes its metadata and publishes ResourceModified in a transaction.
   */
  override def commitStreamabilityFix(
    resourceId: ResourceId,
    tempFile: Option[String]
  ): IO[Either[StreamabilityError, Unit]] =
    getResourceInfo(resourceId).flatMap:
      case None       => IO.pure(Left(StreamabilityError.ResourceNotFound(resourceId)))
      case Some(info) =>
        ops.commitStreamability(info, tempFile).flatMap:
          case Left(error)    => IO.pure(Left(error))
          case Right(updated) =>
            db.transact(s => db.upsertResource(updated) >> topic.publish(s, ResourceModified(updated))).as(Right(()))

  override def reComputePartialHashes(): IO[Unit] = getAllResources.evalMap { resource =>
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
      case Some(info) => ops.derivedResource(info, operation).map(_.map(ResourceContent.Streamed(_)))

  override def generateAllPreviews(): IO[Unit] =
    getAllResources
      .flatMap(info => fs2.Stream.emits(ops.previewOperations(info).map(info -> _)))
      .parEvalMap(parallelFactor) { case (info, operation) => ops.runPreviewOperation(info, operation) }
      .compile.drain

  override def generatePreviewsIfEnabled(info: ResourceInfo): IO[Unit] =
    if config.generatePreviewsOnAdd then ops.generatePreviews(info)
    else IO.unit

  override def deleteFile(info: ResourceInfo): IO[Unit] =
    val path = config.resourcePath.resolve(info.path)
    IO.blocking(path.deleteIfExists()).void

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

  override def importBackup(resources: fs2.Stream[IO, ResourceInfo]): IO[Unit] =
    db.truncateTables() >> resources.map(r => r.copy(resourceId = config.generateId(), title = None))
      .evalMap(resource => IO(logger.info(s"Inserting resource: ${resource.resourceId}")) >> db.insertResource(resource)).compile.drain

}

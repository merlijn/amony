package nl.amony.modules.resources.impl

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.{Files as JFiles, Path as JPath}
import java.security.MessageDigest
import java.time.Instant
import scala.concurrent.duration.*

import cats.data.EitherT
import cats.effect.IO
import cats.implicits.*
import fs2.io.file.{Files, Path}
import fs2.{Chunk, Pipe}
import scribe.Logging

import nl.amony.modules.auth.api.{Role, UserId}
import nl.amony.modules.resources.api.*
import nl.amony.modules.resources.dal.ResourceDatabase
import nl.amony.modules.resources.util.ETag
import nl.amony.platform.messagebus.{JobBus, JobState, MessageTopic}

final private[impl] case class StagedUpload(resourceId: ResourceId, fileName: String, stagingId: StagingId)

class ResourceBucketImpl(
  config: LocalDirectoryConfig,
  db: ResourceDatabase,
  topic: MessageTopic[ResourceEvent],
  ops: BucketOperations,
  store: ResourceStore,
  events: LocalDirectoryEventStore,
  syncer: LocalDirectorySyncer,
  jobBus: JobBus[ResourceJob] = null,
  jobTimeout: FiniteDuration  = 30.minutes
) extends ResourceBucketConsumer,
      Logging {

  override def getResourceInfo(resourceId: ResourceId): IO[Option[ResourceInfo]] = db.getResourceById(id, resourceId)

  override def id: BucketId = BucketId(config.id)

  override def requiredRole: Option[Role] = config.requiredRole

  override def getResourceContent(resourceId: ResourceId): IO[Option[ResourceContent]] =
    getResourceInfo(resourceId).flatMap:
      case None       => IO.pure(None)
      case Some(info) => store.content(info, ETag.forResource(info))

  override def getAllResources: fs2.Stream[IO, ResourceInfo] =
    db.getStream(id)

  override def refresh(): IO[Unit] = syncer.refresh()

  override def processEvent(event: ResourceEvent): IO[Unit] = event match
    case ResourceAdded(resource)                     => generatePreviewsIfEnabled(resource)
    case ResourceModified(resource)                  => generatePreviewsIfEnabled(resource)
    case ResourceThumbnailUpdated(_, resource)       => generatePreviewsIfEnabled(resource)
    case ResourceMovedAndModified(_, _, _, resource) => generatePreviewsIfEnabled(resource)
    case ResourceDeleted(resource)                   => deleteFile(resource)
    case _                                           => IO.unit

  override def reScanAllMetadata(): IO[Unit] = getAllResources.evalMap { resource =>
    ops.contentMeta(ContentLocation.Resource(resource.path)).flatMap {
      case Left(e)     => IO(logger.error(s"Skipping metadata rescan for ${resource.path}", e.cause))
      case Right(meta) =>
        logger.info(s"Updating metadata for ${resource.path}")
        val updated = resource.copy(contentMeta = resource.contentMeta.updatedWith(meta))
        db.transact(s => db.upsertResource(updated) >> topic.publish(s, ResourceModified(updated)))
    }
  }.compile.drain

  override def processJob(job: ResourceJob): IO[Either[String, IO[Either[String, Unit]]]] =
    job match
      case FixStreamability(_, resourceId) =>
        (for
          stagingId <- EitherT(processStreamabilityFix(resourceId)).leftMap(_.message)
        yield commitStreamabilityFix(resourceId, stagingId).map(_.left.map(_.message))).value

      case upload: UploadResource =>
        (for
          prepared <- EitherT(processUpload(upload)).leftMap(_.message)
        yield commitUpload(upload, prepared).map(_.left.map(_.message))).value

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
        val job    = UploadResource(id, staged.resourceId, userId, staged.fileName, staged.stagingId)
        val submit = jobBus
          .submit(job.key, job)
          .handleErrorWith(error => store.discard(staged.stagingId) >> IO.raiseError(error))
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

  private val invalidSequences = List("/", "\\", "..")

  private def decodeFileName(fileName: String): Either[UploadError, String] =
    try Right(URLDecoder.decode(fileName, StandardCharsets.UTF_8))
    catch case _: IllegalArgumentException => Left(UploadError.InvalidFileName(s"File name '$fileName' is invalid"))

  private def calculateHash: Pipe[IO, Byte, MessageDigest] = {
    val initialDigest = config.hashingAlgorithm.newDigest()

    def updateDigest(digest: MessageDigest, chunk: Chunk[Byte]): MessageDigest = {
      digest.update(chunk.toArray)
      digest
    }
    _.chunks.fold(initialDigest)(updateDigest)
  }

  private def hashFile(path: JPath): IO[Either[UploadProcessingError, String]] =
    Files[IO].readAll(Path.fromNioPath(path)).through(calculateHash).compile.last.map {
      case Some(digest) => Right(config.hashingAlgorithm.encodeHash(digest.digest()))
      case None         => Left(UploadProcessingError(path.getFileName.toString, "the uploaded file is empty"))
    }

  /** Streams the request body into the bucket's temp directory. Nothing outside the temp directory is touched. */
  private def stageUpload(fileName: String, source: fs2.Stream[IO, Byte]): IO[Either[UploadError, StagedUpload]] =
    decodeFileName(fileName) match
      case Left(error)                                                 => IO.pure(Left(error))
      case Right(decoded) if invalidSequences.exists(decoded.contains) =>
        IO.pure(Left(UploadError.InvalidFileName(s"File name '$decoded' is invalid")))
      case Right(decoded)                                              =>
        val resourceId = config.generateId()
        val stagingId  = StagingId(s"$resourceId-upload")
        val target     = config.tempPath.resolve(stagingId)
        val stage      =
          for
            _    <- IO.blocking(JFiles.createDirectories(config.tempPath))
            _    <- source.through(Files[IO].writeAll(Path.fromNioPath(target))).compile.drain
            size <- IO.blocking(JFiles.size(target))
          yield
            if size == 0 then Left(UploadError.StorageError(s"Failed to store upload '$decoded': Uploaded file is empty"))
            else Right(StagedUpload(resourceId, decoded, stagingId))
        stage.attempt.flatMap:
          case Right(Right(staged)) => IO.pure(Right(staged))
          case Right(Left(error))   => store.discard(stagingId).as(Left(error))
          case Left(error)          =>
            store.discard(stagingId).as(Left(UploadError.StorageError(s"Failed to store upload '$decoded': ${error.getMessage}")))

  /**
   * Slow, non-transactional part of an upload: scans the staged file and remuxes it when that makes it streamable. The
   * result lives entirely in the temp directory, so a failure leaves nothing observable behind.
   */
  private def processUpload(upload: UploadResource): IO[Either[UploadProcessingError, PreparedUpload]] =
    val location = ContentLocation.Staged(upload.stagingId)
    val tempPath = config.tempPath.resolve(upload.stagingId)
    IO.blocking(JFiles.exists(tempPath)).flatMap:
      case false => IO.pure(Left(UploadProcessingError(upload.stagingId, "the staged upload is no longer available")))
      case true  =>
        (for
          meta      <- EitherT(ops.contentMeta(location)).leftMap(error =>
                         UploadProcessingError(upload.stagingId, s"the uploaded file could not be read: ${error.message}")
                       )
          container  = meta.contentType
                         .filter(_ => meta.streamable.contains(false))
                         .flatMap(VideoContainer.fromContentType)
          remuxed   <- EitherT.right[UploadProcessingError](container match
                         case None            => IO.pure(false)
                         case Some(container) =>
                           ops.normalizeUpload(upload.stagingId, container).attempt.flatMap {
                             case Right(Right(()))   => IO.pure(true)
                             case Right(Left(error)) =>
                               IO(
                                 logger.warn(s"Failed to remux uploaded file '${upload.fileName}': ${error.message}. Storing the original file")
                               ).as(false)
                             case Left(error)        =>
                               logger.warn(s"Failed to remux uploaded file '${upload.fileName}', storing the original file", error)
                               IO.pure(false)
                           })
          hash      <- EitherT(hashFile(tempPath))
          finalMeta <- if remuxed then
            EitherT(ops.contentMeta(location)).leftMap(error =>
              UploadProcessingError(upload.stagingId, s"the uploaded file could not be read: ${error.message}")
            )
          else EitherT.pure[IO, UploadProcessingError](meta)
          _         <- EitherT.liftF[IO, UploadProcessingError, Unit](store.stage(upload.stagingId, tempPath))
        yield PreparedUpload(upload.stagingId, hash, finalMeta)).value

  /**
   * Fast, transactional part of an upload: moves the prepared file into place and inserts the resource. Committing
   * consumes the staged file, so when inserting the resource fails the object is already in the store and a retry will
   * not find a staged file. A durable outbox job would be needed to close that window.
   */
  private def commitUpload(
    upload: UploadResource,
    prepared: PreparedUpload
  ): IO[Either[UploadProcessingError, Unit]] =
    db.getResourceById(upload.bucketId, upload.resourceId).flatMap:
      case Some(_) => store.discard(prepared.stagingId).as(Right(()))
      case None    =>
        store.commit(prepared.stagingId, upload.fileName).flatMap:
          case Left(message) => IO.pure(Left(UploadProcessingError(prepared.stagingId, message)))
          case Right(stored) =>
            val info = ResourceInfo(
              bucketId           = upload.bucketId,
              resourceId         = upload.resourceId,
              userId             = upload.userId,
              path               = stored.path,
              size               = stored.size,
              partialHash        = Some(prepared.partialHash),
              fileKey            = stored.fileKey,
              contentMeta        = prepared.contentMeta,
              timeAdded          = Some(Instant.now().toEpochMilli),
              timeLastModified   = Some(stored.timeLastModified),
              thumbnailTimestamp = None
            )
            events.processEvent(ResourceAdded(info)).as(Right(()))

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
  private def processStreamabilityFix(resourceId: ResourceId): IO[Either[StreamabilityError, Option[StagingId]]] =
    getResourceInfo(resourceId).flatMap:
      case None                                           => IO.pure(Left(StreamabilityError.ResourceNotFound(resourceId)))
      case Some(info) if !info.streamable.contains(false) => IO.pure(Right(None))
      case Some(info)                                     => ops.remuxForStreaming(info)

  /**
   * Commits a remux: replaces the live file, refreshes its metadata and publishes ResourceModified in a transaction.
   */
  private def commitStreamabilityFix(
    resourceId: ResourceId,
    stagingId: Option[StagingId]
  ): IO[Either[StreamabilityError, Unit]] =
    getResourceInfo(resourceId).flatMap:
      case None       => IO.pure(Left(StreamabilityError.ResourceNotFound(resourceId)))
      case Some(info) =>
        ops.commitStreamability(info, stagingId).flatMap:
          case Left(error)    => IO.pure(Left(error))
          case Right(updated) =>
            db.transact(s => db.upsertResource(updated) >> topic.publish(s, ResourceModified(updated))).as(Right(()))

  override def reComputePartialHashes(): IO[Unit] = getAllResources.evalMap { resource =>
    ops.contentPartialHash(ContentLocation.Resource(resource.path)).flatMap {
      case Left(error)        => IO(logger.error(s"Skipping partial hash for ${resource.path}", error.cause))
      case Right(partialHash) =>
        val updated = resource.copy(partialHash = Some(partialHash))
        if resource.partialHash.contains(partialHash) then IO.unit
        else
          IO(logger.info(s"Updating partialHash for ${resource.path} to $partialHash")) >>
            db.transact(s => db.upsertResource(updated) >> topic.publish(s, ResourceModified(updated)))
    }
  }.compile.drain

  override def getOrCreate(resourceId: ResourceId, operation: ResourceOperation): IO[Option[ResourceContent]] =
    getResourceInfo(resourceId).flatMap:
      case None       => IO.pure(None)
      case Some(info) => ops.derivedResource(info, operation)

  override def generateAllPreviews(): IO[Unit] =
    getAllResources.evalMap(ops.generatePreviews).compile.drain

  private def generatePreviewsIfEnabled(info: ResourceInfo): IO[Unit] =
    if config.generatePreviewsOnAdd then ops.generatePreviews(info)
    else IO.unit

  private def deleteFile(info: ResourceInfo): IO[Unit] =
    store.delete(info.path)

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

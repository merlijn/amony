package nl.amony.modules.resources.impl

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import scala.concurrent.duration.*

import cats.data.{EitherT, OptionT}
import cats.effect.IO
import cats.implicits.*
import scribe.Logging

import nl.amony.modules.auth.api.{Role, UserId}
import nl.amony.modules.resources.api.*
import nl.amony.modules.resources.dal.ResourceDatabase
import nl.amony.modules.resources.util.ETag
import nl.amony.platform.messagebus.{JobBus, JobState, MessageTopic}

final private[impl] case class StagedUpload(resourceId: ResourceId, fileName: String, stagingId: StagingId)

class ResourceBucketImpl(
  config: ResourceBucketConfig,
  parallelFactor: Int,
  db: ResourceDatabase,
  topic: MessageTopic[ResourceEvent],
  ops: BucketOperations,
  store: ResourceStore,
  derivedStore: DerivedStore,
  staging: StagingArea,
  events: ResourceEventStore,
  jobBus: JobBus[ResourceJob],
  jobTimeout: FiniteDuration = 30.minutes
) extends ResourceBucketConsumer,
      Logging {

  override def getResourceInfo(resourceId: ResourceId): IO[Option[ResourceInfo]] = db.getResourceById(id, resourceId)

  override def id: BucketId = BucketId(config.id)

  override def requiredRole: Option[Role] = config.requiredRole

  override def getResourceContent(resourceId: ResourceId): IO[Option[ResourceContent]] =
    (for
      info    <- OptionT(getResourceInfo(resourceId))
      content <- OptionT(store.content(info.path, info.contentType, Some(ETag.forResource(info))))
    yield content).value

  override def getAllResources: fs2.Stream[IO, ResourceInfo] =
    db.getStream(id)

  override def processEvent(event: ResourceEvent): IO[Unit] = event match
    case ResourceAdded(resource)                     => generatePreviewsIfEnabled(resource)
    case ResourceModified(resource)                  => derivedStore.deleteForResource(resource.resourceId) >> generatePreviewsIfEnabled(resource)
    case ResourceThumbnailUpdated(_, resource)       => generatePreviewsIfEnabled(resource)
    case ResourceMovedAndModified(_, _, _, resource) => derivedStore.deleteForResource(resource.resourceId) >> generatePreviewsIfEnabled(resource)
    case ResourceDeleted(resource)                   => deleteFile(resource) >> derivedStore.deleteForResource(resource.resourceId)
    case _                                           => IO.unit

  override def reScanAllMetadata(): IO[Unit] = getAllResources.evalMap { resource =>
    ops.contentMeta(ContentLocation.Resource(resource.path)).value.flatMap {
      case Left(e)     => IO(logger.error(s"Skipping metadata rescan for ${resource.path}", e.cause))
      case Right(meta) =>
        val updated = resource.copy(contentMeta = resource.contentMeta.updatedWith(meta))
        if updated == resource then IO.unit
        else
          logger.info(s"Updating metadata for ${resource.path}")
          events.processEvent(ResourceModified(updated))
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
    (for
      _      <- EitherT(checkStreamabilityFix(resourceId))
      job     = FixStreamability(id, resourceId)
      jobId  <- EitherT.liftF(jobBus.submit(job.key, job))
      status <- EitherT.liftF(jobBus.awaitCompletion(jobId, jobTimeout))
      _      <-
        EitherT.cond[IO](status.state == JobState.Succeeded, (), ResourceJobError.Rejected(status.lastError.getOrElse("Failed to fix streamability")))
    yield ()).value

  override def uploadResource(userId: UserId, fileName: String, source: fs2.Stream[IO, Byte]): IO[Either[UploadError, ResourceInfo]] =
    (for
      staged <- EitherT(stageUpload(fileName, source))
      job     = UploadResource(id, staged.resourceId, userId, staged.fileName, staged.stagingId)
      jobId  <- EitherT.liftF(
                  jobBus.submit(job.key, job)
                    .handleErrorWith(error => staging.delete(staged.stagingId) >> IO.raiseError(error))
                )
      status <- EitherT.liftF(jobBus.awaitCompletion(jobId, jobTimeout))
      _      <- EitherT.cond[IO](
                  status.state == JobState.Succeeded,
                  (),
                  UploadError.StorageError(status.lastError.getOrElse("Failed to store the uploaded resource"))
                )
      info   <- EitherT.fromOptionF(
                  getResourceInfo(staged.resourceId),
                  UploadError.StorageError("Uploaded resource was not found after completion")
                )
    yield info).value

  private val invalidSequences = List("/", "\\", "..")

  private def decodeFileName(fileName: String): Either[UploadError, String] =
    try Right(URLDecoder.decode(fileName, StandardCharsets.UTF_8))
    catch case _: IllegalArgumentException => Left(UploadError.InvalidFileName(s"File name '$fileName' is invalid"))

  private def stageUpload(fileName: String, source: fs2.Stream[IO, Byte]): IO[Either[UploadError, StagedUpload]] =
    decodeFileName(fileName) match
      case Left(error)                                                 => IO.pure(Left(error))
      case Right(decoded) if invalidSequences.exists(decoded.contains) =>
        IO.pure(Left(UploadError.InvalidFileName(s"File name '$decoded' is invalid")))
      case Right(decoded)                                              =>
        val resourceId = config.generateId()
        val stagingId  = StagingId(s"$resourceId-upload")
        staging.write(stagingId, source).attempt.flatMap:
          case Right(staged) if staged.size == 0 =>
            staging.delete(stagingId).as(Left(UploadError.StorageError(s"Failed to store upload '$decoded': Uploaded file is empty")))
          case Right(_)                          =>
            IO.pure(Right(StagedUpload(resourceId, decoded, stagingId)))
          case Left(error)                       =>
            staging.delete(stagingId).as(Left(UploadError.StorageError(s"Failed to store upload '$decoded': ${error.getMessage}")))

  private def processUpload(upload: UploadResource): IO[Either[UploadProcessingError, PreparedUpload]] =
    val location = ContentLocation.Staged(upload.stagingId)
    staging.get(upload.stagingId).flatMap:
      case None         => IO.pure(Left(UploadProcessingError(upload.stagingId, "the staged upload is no longer available")))
      case Some(staged) =>
        (for
          _         <- EitherT.cond[IO](staged.size > 0, (), UploadProcessingError(upload.stagingId, "the uploaded file is empty"))
          meta      <- ops.contentMeta(location).leftMap(error =>
                         UploadProcessingError(upload.stagingId, s"the uploaded file could not be read: ${error.message}")
                       )
          container  = meta.contentType
                         .filter(_ => meta.streamable.contains(false))
                         .flatMap(VideoContainer.fromContentType)
          remuxed   <- EitherT.right[UploadProcessingError](container match
                         case None            => IO.pure(false)
                         case Some(container) =>
                           ops.normalizeUpload(upload.stagingId, container).value.attempt.flatMap {
                             case Right(Right(()))   => IO.pure(true)
                             case Right(Left(error)) =>
                               IO(
                                 logger.warn(s"Failed to remux uploaded file '${upload.fileName}': ${error.message}. Storing the original file")
                               ).as(false)
                             case Left(error)        =>
                               logger.warn(s"Failed to remux uploaded file '${upload.fileName}', storing the original file", error)
                               IO.pure(false)
                           })
          hash      <- ops.contentPartialHash(location).leftMap(error =>
                         UploadProcessingError(upload.stagingId, s"the uploaded file could not be hashed: ${error.message}")
                       )
          finalMeta <- if remuxed then
            ops.contentMeta(location).leftMap(error =>
              UploadProcessingError(upload.stagingId, s"the uploaded file could not be read: ${error.message}")
            )
          else EitherT.pure[IO, UploadProcessingError](meta)
        yield PreparedUpload(upload.stagingId, hash, finalMeta)).value

  private def commitUpload(
    upload: UploadResource,
    prepared: PreparedUpload
  ): IO[Either[UploadProcessingError, Unit]] =
    (for
      existing <- EitherT.liftF(db.getResourceById(upload.bucketId, upload.resourceId))
      _        <- existing match
                    case Some(_) => EitherT.liftF(staging.delete(prepared.stagingId))
                    case None    =>
                      for
                        staged <- EitherT.fromOptionF(
                                    staging.get(prepared.stagingId),
                                    UploadProcessingError(prepared.stagingId, s"Staged upload '${prepared.stagingId}' is no longer available")
                                  )
                        stored <- store.put(upload.fileName, staged.path).leftMap(message =>
                                    UploadProcessingError(prepared.stagingId, message)
                                  )
                        _      <- EitherT.liftF(staging.delete(prepared.stagingId))
                        info    = ResourceInfo(
                                    bucketId           = upload.bucketId,
                                    resourceId         = upload.resourceId,
                                    userId             = upload.userId,
                                    path               = stored.path,
                                    size               = stored.size,
                                    partialHash        = prepared.partialHash,
                                    fileKey            = stored.fileKey,
                                    contentMeta        = prepared.contentMeta,
                                    timeAdded          = Some(Instant.now().toEpochMilli),
                                    timeLastModified   = Some(stored.timeLastModified),
                                    thumbnailTimestamp = None
                                  )
                        _      <- EitherT.liftF(events.processEvent(ResourceAdded(info)))
                      yield ()
    yield ()).value

  private def checkStreamabilityFix(resourceId: ResourceId): IO[Either[ResourceJobError, Unit]] =
    getResourceInfo(resourceId).map:
      case None                                                 => Left(ResourceJobError.NotFound)
      case Some(info) if !info.streamable.contains(false)       =>
        Left(ResourceJobError.Rejected("Resource is already streamable or its streamability is unknown"))
      case Some(info) if AddFastStart.forResource(info).isEmpty =>
        Left(ResourceJobError.Rejected("The container format cannot be remuxed for streaming"))
      case Some(_)                                              => Right(())

  private def processStreamabilityFix(resourceId: ResourceId): IO[Either[StreamabilityError, Option[StagingId]]] =
    OptionT(getResourceInfo(resourceId))
      .semiflatMap((info: ResourceInfo) =>
        if !info.streamable.contains(false) then IO.pure(Right(None): Either[StreamabilityError, Option[StagingId]])
        else ops.remuxForStreaming(info).value
      )
      .getOrElse(Left(StreamabilityError.ResourceNotFound(resourceId)))

  private def commitStreamabilityFix(
    resourceId: ResourceId,
    stagingId: Option[StagingId]
  ): IO[Either[StreamabilityError, Unit]] =
    (for
      info        <- EitherT.fromOptionF(getResourceInfo(resourceId), StreamabilityError.ResourceNotFound(resourceId))
      stored      <- replaceOrStatForStreamability(info, stagingId)
      location     = ContentLocation.Resource(info.path)
      partialHash <- ops.contentPartialHash(location).leftMap(e => StreamabilityError.FixFailed(info.path, e.message))
      streamable  <- ops.isStreamable(location, info.contentType).leftMap(e => StreamabilityError.FixFailed(info.path, e.message))
      updated     <- EitherT.cond[IO](
                       streamable.contains(true),
                       info.copy(
                         size             = stored.size,
                         partialHash      = partialHash,
                         fileKey          = stored.fileKey,
                         timeLastModified = Some(stored.timeLastModified),
                         contentMeta      = info.contentMeta.copy(streamable = streamable)
                       ),
                       StreamabilityError.FixFailed(info.path, "the file is not streamable after the remux")
                     )
      _           <- EitherT.liftF(events.processEvent(ResourceModified(updated)))
    yield ()).value

  private def replaceOrStatForStreamability(
    info: ResourceInfo,
    stagingId: Option[StagingId]
  ): EitherT[IO, StreamabilityError, StoredResource] =
    stagingId match
      case Some(id) =>
        EitherT.liftF(staging.get(id)).flatMap {
          case Some(staged) =>
            store.replace(info.path, staged.path)
              .leftMap(message => StreamabilityError.FixFailed(info.path, message))
              .semiflatTap(_ => staging.delete(id))
          case None         =>
            EitherT.fromOptionF(
              store.stat(info.path),
              StreamabilityError.FixFailed(info.path, "resource file is no longer available")
            )
        }
      case None     =>
        EitherT.fromOptionF(
          store.stat(info.path),
          StreamabilityError.FixFailed(info.path, "resource file is no longer available")
        )

  override def reComputePartialHashes(): IO[Unit] = getAllResources.evalMap { resource =>
    ops.contentPartialHash(ContentLocation.Resource(resource.path)).value.flatMap {
      case Left(error)        => IO(logger.error(s"Skipping partial hash for ${resource.path}", error.cause))
      case Right(partialHash) =>
        val updated = resource.copy(partialHash = partialHash)
        if resource.partialHash == partialHash then IO.unit
        else
          IO(logger.info(s"Updating partialHash for ${resource.path} to $partialHash")) >>
            events.processEvent(ResourceModified(updated))
    }
  }.compile.drain

  override def getOrCreate(resourceId: ResourceId, operation: ResourceOperation): IO[Option[ResourceContent]] =
    OptionT(getResourceInfo(resourceId)).flatMapF(getOrCreate(_, operation)).value

  override def getOrCreate(info: ResourceInfo, operation: ResourceOperation): IO[Option[ResourceContent]] =
    ops.derivedResource(info, operation).foldF(
      e => IO(logger.warn(e.message)).as(None),
      content => IO.pure(Some(content))
    )

  override def generateAllPreviews(): IO[Unit] =
    getAllResources.parEvalMap(parallelFactor)(ops.generatePreviews).compile.drain

  private def generatePreviewsIfEnabled(info: ResourceInfo): IO[Unit] =
    if config.generatePreviewsOnAdd then ops.generatePreviews(info)
    else IO.unit

  private def deleteFile(info: ResourceInfo): IO[Unit] =
    store.delete(info.path)

  override def deleteResource(resourceId: ResourceId): IO[Unit] =
    OptionT(getResourceInfo(resourceId))
      .semiflatMap(info => db.transact(s => db.deleteResource(id, resourceId) >> topic.publish(s, ResourceDeleted(info))))
      .value.void

  override def updateUserMeta(resourceId: ResourceId, title: Option[String], description: Option[String], tags: List[String]): IO[Unit] =
    db.transact: s =>
      (for
        existing        <- OptionT(db.getResourceByIdForUpdate(id, resourceId))
        existingUserTags = existing.tags.filter(_.owner == TagOwner.User)
        newUserTags      = tags.toSet.map(Tag.user)
        tagsAdded        = newUserTags -- existingUserTags
        tagsRemoved      = existingUserTags -- newUserTags
        // User-supplied labels always carry the user owner; other owners are preserved.
        updated         <- OptionT(db.updateUserMeta(id, resourceId, title, description, TagOwner.User, tags))
        _               <- OptionT.liftF(
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
                           )
      yield ()).value.void

  /** Maximum resources updated in a single transaction: atomic per batch without holding locks for the whole selection. */
  private val tagBulkBatchSize = 100

  override def updateResourceTags(resourceIds: Set[ResourceId], tagsToAdd: Set[Tag], tagsToRemove: Set[Tag]): IO[Unit] =
    resourceIds.toList.grouped(tagBulkBatchSize).toList.traverse_ : batch =>
      db.transact: s =>
        for
          updated <- batch.flatTraverse(resourceId => db.updateResourceTags(id, resourceId, tagsToAdd, tagsToRemove).map(_.toList))
          events   = updated.map { resource =>
                       ResourceUserMetaUpdated(
                         resource           = resource,
                         updatedDescription = None,
                         updatedTitle       = None,
                         tagsAdded          = tagsToAdd,
                         tagsRemoved        = tagsToRemove
                       )
                     }
          _       <- topic.publishAll(s, events)
        yield ()

  override def updateThumbnailTimestamp(resourceId: ResourceId, timestamp: Int): IO[Unit] =
    db.transact: s =>
      OptionT(db.updateThumbnailTimestamp(id, resourceId, timestamp))
        .semiflatMap(updated => topic.publish(s, ResourceThumbnailUpdated(timestamp, updated)))
        .value.void

  override def importBackup(resources: fs2.Stream[IO, ResourceInfo]): IO[Unit] =
    db.truncateTables() >> resources.map(r => r.copy(resourceId = config.generateId(), title = None))
      .evalMap(resource => IO(logger.info(s"Inserting resource: ${resource.resourceId}")) >> db.insertResource(resource)).compile.drain

}

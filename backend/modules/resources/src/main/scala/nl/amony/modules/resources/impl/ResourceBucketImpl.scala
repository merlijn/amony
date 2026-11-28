package nl.amony.modules.resources.impl

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.{Files as JFiles, Path}
import java.time.Instant
import scala.concurrent.duration.*

import cats.data.{EitherT, OptionT}
import cats.effect.{IO, Resource}
import cats.implicits.*
import fs2.io.file.{Files, Path as Fs2Path}
import scribe.Logging

import nl.amony.modules.auth.api.{Role, UserId}
import nl.amony.modules.resources.api.*
import nl.amony.modules.resources.dal.ResourceDatabase
import nl.amony.modules.resources.util.ETag
import nl.amony.platform.messagebus.{JobBus, JobState, MessageTopic}

class ResourceBucketImpl(
  config: ResourceBucketConfig,
  parallelFactor: Int,
  db: ResourceDatabase,
  topic: MessageTopic[ResourceEvent],
  ops: ResourceOperations,
  store: ResourceStore,
  events: ResourceEventStore,
  jobBus: JobBus[ResourceJob],
  jobTimeout: FiniteDuration = 5.minutes
) extends ResourceBucketConsumer,
      Logging {

  override def getResourceInfo(resourceId: ResourceId): IO[Option[ResourceInfo]] = db.getResourceById(id, resourceId)

  override def id: BucketId = BucketId(config.id)

  override def requiredRole: Option[Role] = config.requiredRole

  override def getResourceContent(resourceId: ResourceId): IO[Option[ResourceContent]] =
    (for
      info    <- OptionT(getResourceInfo(resourceId))
      content <- OptionT(store.content(StoreKey.Resource(info.path), info.contentType, Some(ETag.forResource(info))))
    yield content).value

  override def getAllResources: fs2.Stream[IO, ResourceInfo] =
    db.getStream(id)

  override def processEvent(event: ResourceEvent): IO[Unit] = event match
    case ResourceAdded(resource)                     => generatePreviewsIfEnabled(resource)
    case ResourceModified(resource)                  => store.deleteDerived(resource.resourceId) >> generatePreviewsIfEnabled(resource)
    case ResourceThumbnailUpdated(_, resource)       => generatePreviewsIfEnabled(resource)
    case ResourceMovedAndModified(_, _, _, resource) => store.deleteDerived(resource.resourceId) >> generatePreviewsIfEnabled(resource)
    case ResourceDeleted(resource)                   => deleteFile(resource) >> store.deleteDerived(resource.resourceId)
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
          remux <- EitherT(processStreamabilityFix(resourceId)).leftMap(_.message)
        yield commitStreamabilityFix(resourceId, remux).map(_.left.map(_.message))).value

      case upload: UploadResource =>
        IO.pure(Right(commitUpload(upload)))

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
      job    <- prepareAndStageUpload(userId, fileName, source)
      jobId  <- EitherT.liftF(
                  jobBus.submit(job.key, job)
                    .handleErrorWith(error =>
                      store.delete(StoreKey.Resource(job.path)) >>
                        db.deleteStagedUpload(id, job.path) >>
                        IO.raiseError(error)
                    )
                )
      status <- EitherT.liftF(jobBus.awaitCompletion(jobId, jobTimeout))
      _      <- EitherT.cond[IO](
                  status.state == JobState.Succeeded,
                  (),
                  UploadError.StorageError(status.lastError.getOrElse("Failed to store the uploaded resource"))
                )
      info   <- EitherT.fromOptionF(
                  getResourceInfo(job.resourceId),
                  UploadError.StorageError("Uploaded resource was not found after completion")
                )
    yield info).value

  private val invalidSequences = List("/", "\\", "..")

  private def validateFileName(fileName: String): Either[UploadError, String] =
    for
      decoded <-
        try Right(URLDecoder.decode(fileName, StandardCharsets.UTF_8))
        catch case _: IllegalArgumentException => Left(UploadError.InvalidFileName(s"File name '$fileName' is invalid"))
      _       <- Either.cond(!invalidSequences.exists(decoded.contains), (), UploadError.InvalidFileName(s"File name '$decoded' is invalid"))
    yield decoded

  private def tempUploadFile: Resource[IO, Path] =
    Resource.make(IO.blocking(JFiles.createTempFile("amony-upload-", "")))(path => IO.blocking(JFiles.deleteIfExists(path)).void)

  private def cleanupStaged(targetPath: ResourcePath): IO[Unit] =
    (store.delete(StoreKey.Resource(targetPath)).attempt >> db.deleteStagedUpload(id, targetPath).attempt).void

  private def normalizeIfNeeded(localFile: Path, decoded: String, meta: ContentMeta): IO[Boolean] =
    val container = meta.contentType
      .filter(_ => meta.streamable.contains(false))
      .flatMap(VideoContainer.fromContentType)
    container match
      case None            => IO.pure(false)
      case Some(container) =>
        ops.normalizeUpload(localFile, container).value.attempt.flatMap {
          case Right(Right(()))   => IO.pure(true)
          case Right(Left(error)) =>
            IO(logger.warn(s"Failed to remux uploaded file '$decoded': ${error.message}. Storing the original file")).as(false)
          case Left(error)        =>
            logger.warn(s"Failed to remux uploaded file '$decoded', storing the original file", error)
            IO.pure(false)
        }

  private def stageLocalFile(
    userId: UserId,
    validatedFilename: String,
    resourceId: ResourceId,
    targetPath: ResourcePath,
    title: Option[String],
    localFile: Path,
    source: fs2.Stream[IO, Byte]
  ): EitherT[IO, UploadError, UploadResource] =
    val location                     = ContentLocation.LocalFile(localFile)
    def storageError(detail: String) = UploadError.StorageError(s"Failed to store upload '$validatedFilename': $detail")
    for
      _         <- EitherT.liftF(source.through(Files[IO].writeAll(Fs2Path.fromNioPath(localFile))).compile.drain)
      size      <- EitherT.liftF(IO.blocking(JFiles.size(localFile)))
      _         <- EitherT.cond[IO](size > 0, (), storageError("Uploaded file is empty"))
      meta      <- ops.contentMeta(location).leftMap(e => storageError(e.message))
      remuxed   <- EitherT.liftF(normalizeIfNeeded(localFile, validatedFilename, meta))
      hash      <- ops.contentPartialHash(location).leftMap(e => storageError(e.message))
      finalMeta <- if remuxed then ops.contentMeta(location).leftMap(e => storageError(e.message)) else EitherT.pure[IO, UploadError](meta)
      _         <- EitherT.liftF(db.insertStagedUpload(id, targetPath))
      _         <- store.put(StoreKey.Resource(targetPath), finalMeta.contentType, Files[IO].readAll(Fs2Path.fromNioPath(localFile)))
                     .leftMap(storageError)
    yield UploadResource(id, resourceId, userId, targetPath, title, hash, finalMeta)

  private def prepareAndStageUpload(
    userId: UserId,
    fileName: String,
    source: fs2.Stream[IO, Byte]
  ): EitherT[IO, UploadError, UploadResource] =
    for
      validatedFilename <- EitherT.fromEither[IO](validateFileName(fileName))
      resourceId         = config.generateId()
      targetPath         = store.pathForUpload(resourceId, validatedFilename)
      title              = ResourceBucketImpl.titleFromFileName(validatedFilename)
      job               <- EitherT(
                             tempUploadFile.use { localFile =>
                               stageLocalFile(userId, validatedFilename, resourceId, targetPath, title, localFile, source).value.attempt.flatMap:
                                 case Right(Right(job))  => IO.pure(Right(job))
                                 case Right(Left(error)) => cleanupStaged(targetPath).as(Left(error))
                                 case Left(error)        =>
                                   cleanupStaged(targetPath).as(Left(UploadError.StorageError(s"Failed to store upload '$validatedFilename': ${error.getMessage}")))
                             }
                           )
    yield job

  private def commitUpload(upload: UploadResource): IO[Either[String, Unit]] =
    (for
      existing <- EitherT.liftF(db.getResourceById(upload.bucketId, upload.resourceId))
      _        <- existing match
                    case Some(_) => EitherT.liftF(db.deleteStagedUpload(upload.bucketId, upload.path))
                    case None    =>
                      for
                        stored <- EitherT.fromOptionF(
                                    store.stat(upload.path),
                                    s"Uploaded file '${upload.path}' is no longer available"
                                  )
                        info    = ResourceInfo(
                                    bucketId           = upload.bucketId,
                                    resourceId         = upload.resourceId,
                                    userId             = upload.userId,
                                    path               = stored.path,
                                    size               = stored.size,
                                    partialHash        = upload.partialHash,
                                    fileKey            = stored.fileKey,
                                    contentMeta        = upload.contentMeta,
                                    timeAdded          = Some(Instant.now().toEpochMilli),
                                    timeLastModified   = Some(stored.timeLastModified),
                                    title              = upload.title,
                                    thumbnailTimestamp = None
                                  )
                        _      <- EitherT.liftF(
                                    db.transact { s =>
                                      given Option[skunk.Session[IO]] = Some(s)
                                      db.insertResource(info) >>
                                        db.deleteStagedUpload(upload.bucketId, upload.path) >>
                                        topic.publish(s, ResourceAdded(info))
                                    } >> IO(logger.info(s"[$id] ${ResourceAdded(info)}"))
                                  )
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

  private def processStreamabilityFix(resourceId: ResourceId): IO[Either[StreamabilityError, Option[RemuxResult]]] =
    OptionT(getResourceInfo(resourceId))
      .semiflatMap { (info: ResourceInfo) =>
        if !info.streamable.contains(false) then IO.pure(Right(None): Either[StreamabilityError, Option[RemuxResult]])
        else
          val targetPath = store.pathForReplacement(info.path, info.resourceId)
          val stage      = IO.whenA(targetPath != info.path)(db.insertStagedUpload(id, targetPath))
          val cleanup    = IO.whenA(targetPath != info.path)(
            store.delete(StoreKey.Resource(targetPath)).attempt >> db.deleteStagedUpload(id, targetPath).attempt.void
          )
          stage >> ops.remuxForStreaming(info, targetPath).value.flatTap {
            case Left(_)  => cleanup
            case Right(_) => IO.unit
          }.onError(_ => cleanup)
      }
      .getOrElse(Left(StreamabilityError.ResourceNotFound(resourceId)))

  private def commitStreamabilityFix(
    resourceId: ResourceId,
    remux: Option[RemuxResult]
  ): IO[Either[StreamabilityError, Unit]] =
    remux match
      case None                                       => IO.pure(Right(()))
      case Some(RemuxResult(targetPath, partialHash)) =>
        (for
          current <- EitherT.fromOptionF(getResourceInfo(resourceId), StreamabilityError.ResourceNotFound(resourceId))
          stored  <- EitherT.fromOptionF(store.stat(targetPath), StreamabilityError.FixFailed(targetPath, "resource file is no longer available"))
          updated  = current.copy(
                       path             = stored.path,
                       size             = stored.size,
                       partialHash      = partialHash,
                       fileKey          = stored.fileKey,
                       timeLastModified = Some(stored.timeLastModified),
                       contentMeta      = current.contentMeta.copy(streamable = Some(true))
                     )
          _       <- EitherT.liftF {
                       db.transact { s =>
                         db.upsertResource(updated)
                           >> IO.whenA(targetPath != current.path)(db.deleteStagedUpload(id, targetPath))
                           >> topic.publish(s, ResourceModified(updated))
                       } >>
                         IO(logger.info(s"[$id] ${ResourceModified(updated)}")) >>
                         IO.whenA(targetPath != current.path)(
                           store.delete(StoreKey.Resource(current.path)).handleErrorWith(e =>
                             IO(logger.warn(s"Failed to delete previous object '${current.path}' after remux", e))
                           )
                         )
                     }
        yield ()).value

  def cleanupStagedUploads(cutoffTime: Instant): IO[Unit] =
    db.listStagedUploadsBefore(id, cutoffTime).flatMap { paths =>
      paths.traverse_ { path =>
        (store.delete(StoreKey.Resource(path)) >> db.deleteStagedUpload(id, path))
          .handleErrorWith(e => IO(logger.warn(s"Failed to clean up staged upload '$path' in bucket '$id'", e)))
      }
    }

  def stagedCleanupLoop(interval: FiniteDuration = 1.hour, maxAge: FiniteDuration = 1.hour): IO[Unit] =
    fs2.Stream.fixedRate[IO](interval)
      .evalMap(_ =>
        IO.realTimeInstant
          .flatMap(now => cleanupStagedUploads(now.minusMillis(maxAge.toMillis)))
          .handleErrorWith(e => IO(logger.warn(s"Staged upload cleanup failed for bucket '$id'", e)))
      )
      .compile.drain

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
    store.delete(StoreKey.Resource(info.path))

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

object ResourceBucketImpl:

  private val maxTitleLength = 128

  private[impl] def titleFromFileName(fileName: String): Option[String] =
    val dotIndex = fileName.lastIndexOf('.')
    val base     =
      if dotIndex > 0 && dotIndex < fileName.length - 1 then fileName.substring(0, dotIndex)
      else fileName
    val trimmed  = base.strip()
    Option.when(trimmed.nonEmpty)(trimmed.take(maxTitleLength))

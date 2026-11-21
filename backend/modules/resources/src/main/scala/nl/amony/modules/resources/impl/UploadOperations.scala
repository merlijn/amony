package nl.amony.modules.resources.impl

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.{Files as JFiles, Path as JPath, StandardCopyOption}
import java.security.MessageDigest
import java.time.Instant

import cats.data.EitherT
import cats.effect.IO
import fs2.io.file.{Files, Path}
import fs2.{Chunk, Pipe}
import scribe.Logging
import skunk.Session

import nl.amony.modules.resources.api.*

/** A staged upload: the request body is already in the bucket's temp directory, waiting for the upload command. */
final private[impl] case class StagedUpload(resourceId: ResourceId, fileName: String, stagingId: StagingId)

trait UploadOperations extends LocalResourceSyncer, Logging:

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
  protected def stageUpload(fileName: String, source: fs2.Stream[IO, Byte]): IO[Either[UploadError, StagedUpload]] =
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
          case Right(Left(error))   => discardUpload(stagingId).as(Left(error))
          case Left(error)          =>
            discardUpload(stagingId).as(Left(UploadError.StorageError(s"Failed to store upload '$decoded': ${error.getMessage}")))

  protected def discardUpload(stagingId: StagingId): IO[Unit] =
    store.discard(stagingId) *> IO.blocking(JFiles.deleteIfExists(config.tempPath.resolve(stagingId))).void

  /**
   * Slow, non-transactional part of an upload: scans the staged file and remuxes it when that makes it streamable. The
   * result lives entirely in the temp directory, so a failure leaves nothing observable behind.
   */
  def prepareUpload(upload: UploadResource): IO[Either[UploadProcessingError, PreparedUpload]] =
    val tempPath = config.tempPath.resolve(upload.stagingId)
    IO.blocking(JFiles.exists(tempPath)).flatMap:
      case false => IO.pure(Left(UploadProcessingError(upload.stagingId, "the staged upload is no longer available")))
      case true  =>
        (for
          meta      <- EitherT(ops.contentMetaForPath(tempPath)).leftMap(error =>
                         UploadProcessingError(upload.stagingId, s"the uploaded file could not be read: ${error.message}")
                       )
          container  = meta.contentType
                         .filter(_ => meta.streamable.contains(false))
                         .flatMap(VideoContainer.fromContentType)
          remuxed   <- EitherT.right[UploadProcessingError](container match
                         case None            => IO.pure(false)
                         case Some(container) =>
                           ops.addFastStart(tempPath, container).attempt.flatMap {
                             case Left(error)       =>
                               logger.warn(s"Failed to remux uploaded file '${upload.fileName}', storing the original file", error)
                               IO.pure(false)
                             case Right(normalized) =>
                               IO.blocking(JFiles.move(normalized, tempPath, StandardCopyOption.REPLACE_EXISTING)).as(true)
                           })
          hash      <- EitherT(hashFile(tempPath))
          finalMeta <- if remuxed then
            EitherT(ops.contentMetaForPath(tempPath)).leftMap(error =>
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
  def commitUpload(
    upload: UploadResource,
    prepared: PreparedUpload
  ): IO[Either[UploadProcessingError, Unit]] =
    db.getResourceById(upload.bucketId, upload.resourceId).flatMap:
      case Some(_) => discardUpload(prepared.stagingId).as(Right(()))
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
            processEvent(ResourceAdded(info)).as(Right(()))

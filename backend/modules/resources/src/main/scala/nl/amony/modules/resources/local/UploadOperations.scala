package nl.amony.modules.resources.local

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.BasicFileAttributes
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
final case class StagedUpload(resourceId: ResourceId, fileName: String, tempFile: String)

/** An upload that has been validated, optionally remuxed and scanned, ready to be moved into the resource directory. */
final case class PreparedUpload(tempFile: String, partialHash: String, contentMeta: ContentMeta)

final case class UploadProcessingError(tempFile: String, message: String)

trait UploadOperations extends LocalResourceSyncer, Logging:

  private val invalidSequences = List("/", "\\", "..")

  private def decodeFileName(fileName: String): Either[UploadError, String] =
    try Right(URLDecoder.decode(fileName, StandardCharsets.UTF_8))
    catch case _: IllegalArgumentException => Left(UploadError.InvalidFileName(s"File name '$fileName' is invalid"))

  private def resolveTargetPath(path: JPath, maxAttempt: Int): Either[UploadError, JPath] = {

    var target: JPath = path
    var counter: Int  = 1

    while JFiles.exists(target) && counter < maxAttempt do
      val fileNameWithoutExt = target.getFileName.toString.lastIndexOf('.') match
        case -1  => target.getFileName.toString
        case idx => target.getFileName.toString.substring(0, idx)

      val extension = target.getFileName.toString.lastIndexOf('.') match
        case -1  => ""
        case idx => target.getFileName.toString.substring(idx)

      target = path.getParent.resolve(s"${fileNameWithoutExt}_$counter$extension")
      counter += 1

    if JFiles.exists(target) then
      Left(UploadError.StorageError("Failed to resolve unique file name after 100 attempts"))
    else
      Right(target)
  }

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
  private[local] def stageUpload(fileName: String, source: fs2.Stream[IO, Byte]): IO[Either[UploadError, StagedUpload]] =
    decodeFileName(fileName) match
      case Left(error)                                                 => IO.pure(Left(error))
      case Right(decoded) if invalidSequences.exists(decoded.contains) =>
        IO.pure(Left(UploadError.InvalidFileName(s"File name '$decoded' is invalid")))
      case Right(decoded)                                              =>
        val resourceId = config.generateId()
        val tempFile   = s"$resourceId-upload"
        val target     = config.tempPath.resolve(tempFile)
        val stage      =
          for
            _    <- IO.blocking(JFiles.createDirectories(config.tempPath))
            _    <- source.through(Files[IO].writeAll(Path.fromNioPath(target))).compile.drain
            size <- IO.blocking(JFiles.size(target))
          yield
            if size == 0 then Left(UploadError.StorageError(s"Failed to store upload '$decoded': Uploaded file is empty"))
            else Right(StagedUpload(resourceId, decoded, tempFile))
        stage.attempt.flatMap:
          case Right(Right(staged)) => IO.pure(Right(staged))
          case Right(Left(error))   => discardUpload(tempFile).as(Left(error))
          case Left(error)          =>
            discardUpload(tempFile).as(Left(UploadError.StorageError(s"Failed to store upload '$decoded': ${error.getMessage}")))

  private[local] def discardUpload(tempFile: String): IO[Unit] =
    IO.blocking(JFiles.deleteIfExists(config.tempPath.resolve(tempFile))).void

  /**
   * Slow, non-transactional part of an upload: scans the staged file and remuxes it when that makes it streamable. The
   * result lives entirely in the temp directory, so a failure leaves nothing observable behind.
   */
  private[local] def prepareUpload(upload: UploadResource): IO[Either[UploadProcessingError, PreparedUpload]] =
    val tempPath = config.tempPath.resolve(upload.tempFile)
    IO.blocking(JFiles.exists(tempPath)).flatMap:
      case false => IO.pure(Left(UploadProcessingError(upload.tempFile, "the staged upload is no longer available")))
      case true  =>
        (for
          meta      <- EitherT(ops.contentMetaForPath(tempPath)).leftMap(error =>
                         UploadProcessingError(upload.tempFile, s"the uploaded file could not be read: ${error.message}")
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
              UploadProcessingError(upload.tempFile, s"the uploaded file could not be read: ${error.message}")
            )
          else EitherT.pure[IO, UploadProcessingError](meta)
        yield PreparedUpload(upload.tempFile, hash, finalMeta)).value

  /** Fast, transactional part of an upload: moves the prepared file into place and inserts the resource. */
  private[local] def commitUpload(
    session: Session[IO],
    upload: UploadResource,
    prepared: PreparedUpload
  ): IO[Either[UploadProcessingError, Unit]] =
    db.getResourceById(upload.bucketId, upload.resourceId)(using Some(session)).flatMap:
      case Some(_) => discardUpload(prepared.tempFile).as(Right(()))
      case None    =>
        resolveTargetPath(config.resourcePath.resolve(upload.fileName), 100) match
          case Left(error)       =>
            val message = error match
              case UploadError.InvalidFileName(message) => message
              case UploadError.StorageError(message)    => message
            IO.pure(Left(UploadProcessingError(prepared.tempFile, message)))
          case Right(targetPath) =>
            for
              _     <- IO.blocking(JFiles.createDirectories(targetPath.getParent))
              _     <- IO.blocking(JFiles.move(config.tempPath.resolve(prepared.tempFile), targetPath))
              attrs <- IO.blocking(JFiles.readAttributes(targetPath, classOf[BasicFileAttributes]))
              info   = ResourceInfo(
                         bucketId           = upload.bucketId,
                         resourceId         = upload.resourceId,
                         userId             = upload.userId,
                         path               = config.resourcePath.relativize(targetPath).toString,
                         size               = attrs.size(),
                         partialHash        = Some(prepared.partialHash),
                         fileKey            = Option(attrs.fileKey()).map(_.toString),
                         contentMeta        = prepared.contentMeta,
                         timeAdded          = Some(Instant.now().toEpochMilli),
                         timeLastModified   = Some(attrs.lastModifiedTime().toMillis),
                         thumbnailTimestamp = None
                       )
              _     <- processEvent(session, ResourceAdded(info))
            yield Right(())

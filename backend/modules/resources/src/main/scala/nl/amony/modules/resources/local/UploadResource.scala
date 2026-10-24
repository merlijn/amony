package nl.amony.modules.resources.local

import java.nio.file.{Files as JFiles, Path as JPath, StandardCopyOption}
import java.security.MessageDigest

import cats.data.EitherT
import cats.effect.IO
import fs2.io.file.{Files, Path}
import fs2.{Chunk, Pipe}
import scribe.Logging

import nl.amony.modules.auth.api.UserId
import nl.amony.modules.resources.api.{ResourceAdded, ResourceBucket, ResourceInfo, UploadError, VideoContainer}
import nl.amony.platform.files.FileInfo

trait UploadResource extends LocalResourceSyncer, ResourceBucket, Logging:

  self: LocalResourceOperations =>

  private val invalidSequences = List("/", "\\", "..")

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

  override def uploadResource(userId: UserId, fileName: String, source: fs2.Stream[IO, Byte]): IO[Either[UploadError, ResourceInfo]] =

    if invalidSequences.exists(fileName.contains) then
      IO.pure(Left(UploadError.InvalidFileName(s"File name '$fileName' is invalid")))
    else

      val temporaryFileName = s"${config.random.alphanumeric.take(8).mkString}_$fileName"

      val uploadPath                                   = config.uploadPath.resolve(temporaryFileName)
      val writeToFile: Pipe[IO, Byte, Nothing]         = Files[IO].writeAll(Path.fromNioPath(uploadPath))
      def calculateHash: Pipe[IO, Byte, MessageDigest] = {

        val initialDigest = config.hashingAlgorithm.newDigest()

        def updateDigest(digest: MessageDigest, chunk: Chunk[Byte]): MessageDigest = {
          digest.update(chunk.toArray)
          digest
        }
        _.chunks.fold(initialDigest)(updateDigest)
      }

      def insertResource(digest: Array[Byte]): EitherT[IO, UploadError, ResourceInfo] = {
        val encodedPartialHash = config.hashingAlgorithm.encodeHash(digest)

        /**
         * TODO 
         * 
         * 1. There are a bunch of steps which are not guaranteed to all succeed or fail together (no transactionality)
         * 2. The file extension might not be correct for the content type, we might want to validate or change the extension
         */
        for
          targetPath   <- EitherT.fromEither[IO](resolveTargetPath(config.resourcePath.resolve(fileName), 100))
          _            <- EitherT.right[UploadError](IO(JFiles.move(uploadPath, targetPath)))
          resourceInfo <- EitherT.right[UploadError](newResource(FileInfo(targetPath, encodedPartialHash), userId))
          _            <- EitherT.right[UploadError](processEvent(ResourceAdded(resourceInfo)))
        yield resourceInfo
      }

      def hashUploadedFile(): IO[Array[Byte]] =
        Files[IO].readAll(Path.fromNioPath(uploadPath)).through(calculateHash).compile.last
          .map(_.getOrElse(throw new RuntimeException(s"Failed to compute partialHash for uploaded file: $fileName")).digest())

      /** Remuxes the stored upload into a streamable layout when its container supports it and it is not streamable yet. */
      def remuxUploadedFile(): IO[Option[Array[Byte]]] =
        // TODO reuse this metadata in newResource to avoid scanning the upload twice.
        contentMetaForPath(uploadPath).flatMap { maybeMeta =>
          val container = maybeMeta
            .filter(_.streamable.contains(false))
            .flatMap(_.contentType)
            .flatMap(VideoContainer.fromContentType)

          container match
            case Some(container) =>
              addFastStart(uploadPath, container).attempt.flatMap {
                case Left(error)       =>
                  logger.warn(s"Failed to remux uploaded file '$fileName', storing the original file", error)
                  IO.pure(None)
                case Right(normalized) =>
                  IO(JFiles.move(normalized, uploadPath, StandardCopyOption.REPLACE_EXISTING)) >> hashUploadedFile().map(Some(_))
              }
            case None            => IO.pure(None)
        }

      (IO(JFiles.createDirectories(config.uploadPath)) >> source.observe(writeToFile).through(calculateHash).compile.last).flatMap {
        case Some(streamDigest) =>
          remuxUploadedFile().flatMap {
            case Some(remuxedDigest) => insertResource(remuxedDigest).value
            case None                => insertResource(streamDigest.digest()).value
          }
        case None               => IO.raiseError(new RuntimeException("Failed to compute partialHash for uploaded file"))
      }.recoverWith(e =>
        IO(JFiles.deleteIfExists(uploadPath)).void >> IO.raiseError(new RuntimeException(s"Failed to upload resource: $fileName", e))
      )

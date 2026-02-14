package nl.amony.modules.resources.local

import java.nio.file.{Files as JFiles, Path as JPath}
import java.security.MessageDigest

import cats.data.EitherT
import cats.effect.IO
import fs2.{Chunk, Pipe}
import scribe.Logging

import nl.amony.lib.files.watcher.FileInfo
import nl.amony.modules.auth.api.UserId
import nl.amony.modules.resources.api.{ResourceAdded, ResourceBucket, ResourceInfo, UploadError}

trait UploadResource extends LocalResourceSyncer, ResourceBucket, Logging:

  private val invalidSequences = List("/", "\\", "..")
  private val files            = summon[fs2.io.file.Files[IO]]

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
      val writeToFile: Pipe[IO, Byte, Nothing]         = fs2.io.file.Files[IO].writeAll(fs2.io.file.Path.fromNioPath(uploadPath))
      val calculateHash: Pipe[IO, Byte, MessageDigest] = {

        val initialDigest = config.hashingAlgorithm.newDigest()

        def updateDigest(digest: MessageDigest, chunk: Chunk[Byte]): MessageDigest = {
          digest.update(chunk.toArray)
          digest
        }
        _.chunks.fold(initialDigest)(updateDigest)
      }

      def insertResource(digest: Array[Byte]): EitherT[IO, UploadError, ResourceInfo] = {
        val encodedHash = config.hashingAlgorithm.encodeHash(digest)

        /**
         * TODO 
         * 
         * 1. There are a bunch of steps which are not guaranteed to all succeed or fail together (no transactionality)
         * 2. The file extension might not be correct for the content type, we might want to validate or change the extension
         */
        for
          targetPath   <- EitherT.fromEither[IO](resolveTargetPath(config.resourcePath.resolve(fileName), 100))
          _            <- EitherT.right[UploadError](IO(JFiles.move(uploadPath, targetPath)))
          resourceInfo <- EitherT.right[UploadError](newResource(FileInfo(targetPath, encodedHash), userId))
          _            <- EitherT.right[UploadError](processEvent(ResourceAdded(resourceInfo)))
        yield resourceInfo
      }

      source.observe(writeToFile).through(calculateHash).compile.last.flatMap {
        case Some(digest) => insertResource(digest.digest()).value
        case None         => IO.raiseError(new RuntimeException("Failed to compute hash for uploaded file"))
      }.recoverWith(e => fs2.io.file.Files[IO].delete(uploadPath) >> IO.raiseError(new RuntimeException(s"Failed to upload resource: $fileName", e)))

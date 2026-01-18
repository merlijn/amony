package nl.amony.modules.resources.local

import java.security.MessageDigest

import cats.effect.IO
import fs2.{Chunk, Pipe}
import scribe.Logging

import nl.amony.lib.files.watcher.FileInfo
import nl.amony.modules.auth.api.UserId
import nl.amony.modules.resources.api.{ResourceAdded, ResourceBucket, ResourceInfo}

trait UploadResource extends LocalResourceSyncer, ResourceBucket, Logging:

  override def uploadResource(userId: UserId, fileName: String, source: fs2.Stream[IO, Byte]): IO[ResourceInfo] =

    val uploadPath = config.uploadPath.resolve(fileName)
    val targetPath = config.resourcePath.resolve(fileName)

    val writeToFile: Pipe[IO, Byte, Nothing]         = fs2.io.file.Files[IO].writeAll(uploadPath)
    val calculateHash: Pipe[IO, Byte, MessageDigest] = {

      val initialDigest = config.hashingAlgorithm.newDigest()

      def updateDigest(digest: MessageDigest, chunk: Chunk[Byte]): MessageDigest = {
        digest.update(chunk.toArray)
        digest
      }
      _.chunks.fold(initialDigest)(updateDigest)
    }

    def insertResource(digest: Array[Byte]): IO[ResourceInfo] = {
      val encodedHash = config.hashingAlgorithm.encodeHash(digest)

      for
        resourceInfo <- newResource(FileInfo(uploadPath, encodedHash), userId)
        _            <- processEvent(ResourceAdded(resourceInfo))
        //        _            <- IO(uploadPath.moveTo(targetPath))
      yield resourceInfo
    }

    source.observe(writeToFile).through(calculateHash).compile.last.flatMap {
      case Some(digest) => insertResource(digest.digest())
      case None         => IO.raiseError(new RuntimeException("Failed to compute hash for uploaded file"))
    }.recoverWith(e => fs2.io.file.Files[IO].delete(uploadPath) >> IO.raiseError(new RuntimeException(s"Failed to upload resource: $fileName", e)))

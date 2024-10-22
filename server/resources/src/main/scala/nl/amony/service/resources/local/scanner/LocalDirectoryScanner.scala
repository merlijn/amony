package nl.amony.service.resources.local.scanner

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import fs2.Stream
import nl.amony.service.resources.ResourceConfig.LocalDirectoryConfig
import nl.amony.service.resources.Resource
import nl.amony.service.resources.api.events.*
import nl.amony.service.resources.api.operations.ResourceOperation
import nl.amony.service.resources.api.{ResourceInfo, ResourceMeta}
import nl.amony.service.resources.local.LocalResourceMeta
import nl.amony.service.resources.local.db.LocalDirectoryDb
import scribe.Logging
import slick.jdbc.JdbcProfile

import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{Files, Path}
import scala.concurrent.duration.FiniteDuration

case class FileInfo(relativePath: Path, hash: String, size: Long, creationTime: Long, modifiedTime: Long)

sealed trait FileEvent
case class FileAdded(fileInfo: FileInfo) extends FileEvent
case class FileDeleted(fileInfo: FileInfo) extends FileEvent
case class FileMoved(hash: String, oldPath: Path, newPath: Path) extends FileEvent

class LocalDirectoryScanner(config: LocalDirectoryConfig)(implicit runtime: IORuntime) extends Logging {

  private val hashingAlgorithm = config.hashingAlgorithm
  private val mediaPath = config.resourcePath
  
  private def scanDirectory(previousState: Set[FileInfo]): Stream[IO, FileEvent] = {
    
    def filterPath(path: Path) = config.filterFileName(path.getFileName.toString)

    // this depends on file system meta data and the fact that a file move does not update these attributes
    def hasEqualMeta(a: FileInfo, b: FileInfo) =
      a.hash == b.hash && a.creationTime == b.creationTime && a.modifiedTime == b.modifiedTime

    def currentFiles = RecursiveFileVisitor.listFilesInDirectoryRecursive(mediaPath, filterPath)
    val previousFiles = previousState.map(f => mediaPath.resolve(f.relativePath))

    logger.debug(s"Scanning directory: ${mediaPath.toAbsolutePath}, previous state size: ${previousState.size}, last modified: ${Files.getLastModifiedTime(mediaPath).toInstant}")

    // new files are either added or moved
    val (moved, added) = (currentFiles.toSet -- previousFiles).partitionMap { path =>
      val hash = hashingAlgorithm.createHash(path)

      val relativePath = mediaPath.relativize(path)
      val creationTime = Files.readAttributes(path, classOf[BasicFileAttributes]).creationTime().toMillis
      val modifiedTime = Files.getLastModifiedTime(path).toMillis
      val size         = Files.size(path)

      // a file that is just moved should have the same hash, creation time, modified time and size
      def equalMeta(r: FileInfo) = r.hash == hash && r.creationTime == creationTime && r.modifiedTime == modifiedTime && r.size == size

      previousState.find(equalMeta) match {

        case Some(old) => Left(FileMoved(old.hash, old.relativePath, relativePath))
        case None    =>
          val contentType = Resource.contentTypeForPath(path)
          val fileInfo = FileInfo(
            relativePath = relativePath,
            hash = hash,
            size = size,
            creationTime,
            modifiedTime)

          Right(FileAdded(fileInfo))
      }
    }

    val deleted = previousState
      .filterNot(f => currentFiles.exists(p => mediaPath.relativize(p) == f.relativePath))
      .filterNot(f => moved.exists(_.hash == f.hash))
      .map(r => FileDeleted(r))

    Stream.emit(moved.toSeq ++ added.toSeq ++ deleted.toSeq).flatMap(Stream.emits)
  }

  private def applyEvent(state: Set[FileInfo], e: FileEvent): Set[FileInfo] = e match {
    case FileAdded(fileInfo) =>
      state + fileInfo
    case FileDeleted(fileInfo) =>
      state.filterNot(_.relativePath == fileInfo.relativePath)
    case FileMoved(hash, oldPath, newPath) =>
      state.map { r =>
        if (r.relativePath == oldPath) r.copy(relativePath = newPath)
        else r
      }
  }

  extension [F[_], T](stream: Stream[F, T])
    def foldFlatMapLast[S](initial: S)(foldFn: (S, T) => S, fn: S => Stream[F, T]): Stream[F, T] = {

      val r = stream.map(Some(_)) ++ Stream.emit[F, Option[T]](None)

      val f: Stream[F, (S, Option[T])] = r.scan[(S, Option[T])](initial -> None) {
        case ((acc, p), Some(e)) => foldFn(acc, e) -> Some(e)
        case ((acc, p), None) => acc -> None
      }

      f.tail.flatMap:
        case (s, Some(t)) => Stream.emit(t)
        case (s, None) => fn(s)
    }

  /**
   * Given an initial state, this method will poll the directory for changes and emit events for new, deleted and moved resources.
   * 
   * The state will be kept in memory and is not persisted.
   */
  def pollingStream(initialState: Set[FileInfo], pollInterval: FiniteDuration): Stream[IO, FileEvent] = {

    def unfoldRecursive(s: Set[FileInfo]): Stream[IO, FileEvent] =
      scanDirectory(s).foldFlatMapLast(s)(applyEvent, s => Stream.sleep[IO](pollInterval) >> unfoldRecursive(s))

    Stream.suspend(unfoldRecursive(initialState))
  }

  /**
   * Given an initial state, this method will poll the directory for changes and emit events for new, deleted and moved resources.
   *
   * The state will be kept in memory and is not persisted.
   */
  def pollingResourceEventStream(initialState: Set[ResourceInfo], pollInterval: FiniteDuration): Stream[IO, ResourceEvent] = {

    val initialFiles = initialState.map { r =>
      FileInfo(Path.of(r.path), r.hash, r.size, r.creationTime.getOrElse(0), r.lastModifiedTime.getOrElse(0))
    }

    pollingStream(initialFiles, pollInterval).parEvalMap(4) {
      case FileAdded(f) =>

        val absolutePath = mediaPath.resolve(f.relativePath)

        for {
          meta <- LocalResourceMeta.resolveMeta(absolutePath).map(_.getOrElse(ResourceMeta.Empty))
        } yield ResourceAdded(ResourceInfo(
          bucketId = config.id,
          path = f.relativePath.toString,
          hash = f.hash,
          size = f.size,
          contentType = Resource.contentTypeForPath(absolutePath),
          contentMeta = meta,
          creationTime = Some(f.creationTime),
          lastModifiedTime = Some(f.modifiedTime)
        ))

      case FileDeleted(f) =>
        IO(ResourceDeleted(f.hash))

      case FileMoved(hash, oldPath, newPath) =>
        IO(ResourceMoved(hash, oldPath.toString, newPath.toString))
    }
  }
}

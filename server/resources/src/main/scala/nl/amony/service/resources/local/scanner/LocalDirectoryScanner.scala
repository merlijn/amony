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

case class FileInfo(path: Path, hash: String, size: Long, creationTime: Long, modifiedTime: Long)

sealed trait FileEvent
case class FileAdded(fileInfo: FileInfo) extends FileEvent
case class FileDeleted(fileInfo: FileInfo) extends FileEvent
case class FileMoved(oldPath: Path, newPath: Path) extends FileEvent

class LocalDirectoryScanner(config: LocalDirectoryConfig)(implicit runtime: IORuntime) extends Logging {

  private val hashingAlgorithm = config.hashingAlgorithm
  private val mediaPath = config.resourcePath
  
  private def scanDirectory(previousState: Set[FileInfo]): Stream[IO, FileEvent] = {
    
    def filterPath(path: Path) = config.filterFileName(path.getFileName.toString)

    // this depends on file system meta data and the fact that a file move does not update these attributes
    def hasEqualMeta(a: FileInfo, b: FileInfo) =
      a.hash == b.hash && a.creationTime == b.creationTime && a.modifiedTime == b.modifiedTime

    def currentFiles = RecursiveFileVisitor.listFilesInDirectoryRecursive(mediaPath, filterPath)
    
    logger.debug(s"Scanning directory: ${mediaPath.toAbsolutePath}, previous state size: ${previousState.size}, last modified: ${Files.getLastModifiedTime(mediaPath).toInstant}")

    val previousFiles = previousState.map(_.path).map(mediaPath.resolve)

    // new files are either added or moved
    val (moved, added) = (currentFiles.toSet -- previousFiles).partitionMap { path =>
      val hash = hashingAlgorithm.createHash(path)

      val relativePath = mediaPath.relativize(path)
      val creationTime = Files.readAttributes(path, classOf[BasicFileAttributes]).creationTime().toMillis
      val modifiedTime = Files.getLastModifiedTime(path).toMillis
      val size         = Files.size(path)

      def equalMeta(r: FileInfo) = r.hash == hash && r.creationTime == creationTime && r.modifiedTime == modifiedTime && r.size == size

      previousState.find(equalMeta) match {

        case Some(old) => Left(FileMoved(old.path, relativePath))
        case None    =>
          val contentType = Resource.contentTypeForPath(path)
          val fileInfo = FileInfo(
            path = relativePath,
            hash = hash,
            size = size,
            creationTime,
            modifiedTime)

          Right(FileAdded(fileInfo))
      }
    }

    val deleted = previousState.filterNot { r =>
      currentFiles.exists(p => r.path == mediaPath.relativize(p))
    }.map(r => FileDeleted(r))

    Stream.emit(moved.toSeq ++ added.toSeq ++ deleted.toSeq).flatMap(Stream.emits)
  }

  private def applyEvent(state: Set[FileInfo], e: FileEvent): Set[FileInfo] = e match {
    case FileAdded(fileInfo) =>
      state + fileInfo
    case FileDeleted(fileInfo) =>
      state.filterNot(_.path == fileInfo.path)
    case FileMoved(oldPath, newPath) =>
      state.map { r =>
        if (r.path == oldPath) r.copy(path = newPath)
        else r
      }
  }
  
  def scanFile(absolutePath: Path, getByPath: String => Option[ResourceInfo], getByHash: String => Option[ResourceInfo]): IO[ResourceInfo] = {
    val relativePath = mediaPath.relativize(absolutePath).toString
    val fileAttributes = Files.readAttributes(absolutePath, classOf[BasicFileAttributes])

    for {
      hash <- IO {
        if (config.verifyExistingHashes) {
          hashingAlgorithm.createHash(absolutePath)
        } else {
          getByPath(relativePath) match {
            case None => hashingAlgorithm.createHash(absolutePath)
            case Some(m) =>
              // if the last modified time is equal to what we have seen before, we assume the hash is still valid
              if (m.lastModifiedTime == Some(fileAttributes.lastModifiedTime().toMillis)) {
                m.hash
              } else {
                logger.warn(s"$absolutePath has been modified since last seen, recomputing hash")
                hashingAlgorithm.createHash(absolutePath)
              }
          }
        }
      }
      meta <-
        getByHash(hash) match {
          case None =>
            logger.info(s"Scanning new file $relativePath")
            LocalResourceMeta.resolveMeta(absolutePath).map(_.getOrElse(ResourceMeta.Empty))
          case Some(m) => IO.pure(m.contentMeta)
        }

    } yield {
      ResourceInfo(
        bucketId = config.id,
        path = relativePath,
        hash = hash,
        size = fileAttributes.size(),
        contentType = Resource.contentTypeForPath(absolutePath), // apache tika?
        contentMeta = meta,
        Some(fileAttributes.creationTime().toMillis),
        Some(fileAttributes.lastModifiedTime().toMillis))
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
}

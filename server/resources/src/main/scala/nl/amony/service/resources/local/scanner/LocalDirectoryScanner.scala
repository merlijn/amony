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
case class FileMoved(fileInfo: FileInfo, oldPath: Path) extends FileEvent


extension [F[_], T](stream: Stream[F, T])
  def foldFlatMap[S](initial: S)(foldFn: (S, T) => S, nextFn: S => Stream[F, T]): Stream[F, T] = {

    val r = stream.map(Some(_)) ++ Stream.emit[F, Option[T]](None)

    val f: Stream[F, (S, Option[T])] = r.scan[(S, Option[T])](initial -> None):
      case ((acc, p), Some(e)) => foldFn(acc, e) -> Some(e)
      case ((acc, p), None)    => acc -> None

    f.tail.flatMap:
      case (s, Some(t)) => Stream.emit(t)
      case (s, None)    => nextFn(s)
  }

class LocalDirectoryScanner(config: LocalDirectoryConfig)(implicit runtime: IORuntime) extends Logging {

  private val hashingAlgorithm = config.hashingAlgorithm
  private val mediaPath = config.resourcePath

  /**
   * In principle, between the last time the directory was scanned and now, **all** files could be renamed. For example
   * using an automated renaming tool or script.
   * 
   * This means, the only thing we can rely on for checking equality is the metadata.
   * 
   */
  private def scanDirectory(previousState: Set[FileInfo]): Stream[IO, FileEvent] = {
    
    def filterPath(path: Path) = config.filterFileName(path.getFileName.toString)

    // a file that is just moved should have the same hash, creation time, modified time and size
    def hasEqualMeta(a: FileInfo)(b: FileInfo): Boolean =
      a.hash == b.hash && a.creationTime == b.creationTime && a.modifiedTime == b.modifiedTime

    def currentFiles = RecursiveFileVisitor.listFilesInDirectoryRecursive(mediaPath, filterPath).toSet
    val previousFiles = previousState.map(f => mediaPath.resolve(f.relativePath))

    logger.debug(s"Scanning directory: ${mediaPath.toAbsolutePath}, previous state size: ${previousState.size}, last modified: ${Files.getLastModifiedTime(mediaPath).toInstant}")

    // new files are either added or moved
    val movedOrAdded = Stream.emits((currentFiles -- previousFiles).toSeq).evalMap { path =>

      for {
        hash    <- hashingAlgorithm.createHash(path)
        fileInfo = FileInfo(
          relativePath = mediaPath.relativize(path),
          hash         = hash,
          size         = Files.size(path),
          creationTime = Files.readAttributes(path, classOf[BasicFileAttributes]).creationTime().toMillis,
          modifiedTime = Files.getLastModifiedTime(path).toMillis
        )
      } yield
        previousState.find(hasEqualMeta(fileInfo)) match
          case Some(old) => FileMoved(fileInfo, old.relativePath)
          case None      => FileAdded(fileInfo)
    }

    // removed files might be deleted or moved
    val maybeDeleted = previousState.filterNot(f => currentFiles.contains(mediaPath.resolve(f.relativePath)))

    def filterMoved(state: Set[FileInfo], e: FileEvent): Set[FileInfo] = e match
      case FileMoved(fileInfo, oldPath) => state.filterNot(_.relativePath == oldPath)
      case _                            => state

    def emitDeleted(deleted: Set[FileInfo]): Stream[IO, FileEvent] = Stream.emits(deleted.toSeq.map(r => FileDeleted(r)))

    movedOrAdded.foldFlatMap(maybeDeleted)(filterMoved, emitDeleted)
  }

  private def applyEvent(state: Set[FileInfo], e: FileEvent): Set[FileInfo] = e match {
    case FileAdded(fileInfo) =>
      state + fileInfo
    case FileDeleted(fileInfo) =>
      state.filterNot(_.relativePath == fileInfo.relativePath)
    case FileMoved(fileInfo, oldPath) =>
      state.map { r =>
        if (r.relativePath == oldPath) r.copy(relativePath = fileInfo.relativePath)
        else r
      }
  }


  /**
   * Given an initial state, this method will poll the directory for changes and emit events for new, deleted and moved resources.
   * 
   * The state will be kept in memory and is not persisted.
   */
  def pollingStream(initialState: Set[FileInfo], pollInterval: FiniteDuration): Stream[IO, FileEvent] = {

    def unfoldRecursive(s: Set[FileInfo]): Stream[IO, FileEvent] = {
      val startTime = System.currentTimeMillis()
      logger.info(s"Scanning directory: ${mediaPath.toAbsolutePath}")
      def logTime = Stream.eval(IO { logger.info(s"Scanning took: ${System.currentTimeMillis() - startTime} ms") })
      scanDirectory(s).foldFlatMap(s)(applyEvent, s => logTime >> Stream.sleep[IO](pollInterval) >> unfoldRecursive(s))
    }

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

        val resourceMeta: IO[ResourceMeta] =
          LocalResourceMeta.resolveMeta(absolutePath)
            .map(_.getOrElse(ResourceMeta.Empty))
            .recover {
              case e => logger.error(s"Failed to resolve meta for $absolutePath", e); ResourceMeta.Empty
            }

        for {
          meta <- resourceMeta
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

      case FileMoved(fileInfo, oldPath) =>
        IO(ResourceMoved(fileInfo.hash, oldPath.toString, fileInfo.relativePath.toString))
    }
  }
}

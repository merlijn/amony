package nl.amony.lib.files.watcher

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import fs2.Stream
import scribe.Logging
import slick.jdbc.JdbcProfile

import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{Files, Path}
import java.time.Instant
import scala.concurrent.duration.FiniteDuration

//trait FileStore {
//  def getByPath(path: Path): IO[Option[FileInfo]]
//  def deletePath(path: Path): IO[Unit]
//  def getAll(): Seq[FileInfo]
//}

case class FileInfo(path: Path, hash: String, size: Long, creationTime: Long, modifiedTime: Long) {
  def equalFileMeta(path: Path, attrs: BasicFileAttributes): Boolean =
    size == Files.size(path) && creationTime == attrs.creationTime().toMillis && modifiedTime == Files.getLastModifiedTime(path).toMillis
}

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

object LocalDirectoryScanner extends Logging {

  /**
   * In principle, between the last time the directory was scanned and now, **all** files could be renamed. For example
   * using an automated renaming tool or script.
   * 
   * This means, the only thing we can rely on for checking equality is the metadata.
   * 
   */
  private def scanDirectory(directory: Path, previousState: Map[Path, FileInfo], filter: Path => Boolean, hashFn: Path => IO[String]): Stream[IO, FileEvent] = {
    
    val previousByHash: Map[String, FileInfo] = previousState.values.foldLeft(Map.empty)((acc, f) => acc + (f.hash -> f) )

    def getByPath(path: Path): Option[FileInfo]   = previousState.get(path)
    def getByHash(hash: String): Option[FileInfo] = previousByHash.get(hash)

    val start = System.currentTimeMillis()

    val currentFiles  = RecursiveFileVisitor.listFilesInDirectoryRecursive(directory, filter)

    logger.debug(s"Listed directory files in ${System.currentTimeMillis() - start} ms")

    // new files are either added or moved
    val movedOrAdded = Stream.emits(currentFiles).evalMap { (path, attrs) =>

      for {
        _          <- IO.unit
        prevByPath = getByPath(path)
        hash       <- prevByPath
                        .filter(_.equalFileMeta(path, attrs))
                        .map(i => IO.pure(i.hash))
                        .getOrElse(hashFn(path))
        fileInfo   = FileInfo(
          path         = path,
          hash         = hash,
          size         = attrs.size(),
          creationTime = attrs.creationTime().toMillis,
          modifiedTime = attrs.lastModifiedTime().toMillis
        )
      } yield
        prevByPath match
          case Some(`fileInfo`) => None
          case _                =>
            getByHash(hash) match {
              case Some(oldFileInfo) if oldFileInfo.equalFileMeta(path, attrs) =>
                Some(FileMoved(fileInfo, oldFileInfo.path))
              case _              =>
                Some(FileAdded(fileInfo))
            }
    }

    val maybeDeleted = currentFiles.foldLeft(previousState){ case (acc, (p, _)) => acc - p }

    def filterMoved(maybeDeleted: Map[Path, FileInfo], e: Option[FileEvent]): Map[Path, FileInfo] = e match
      case Some(FileMoved(fileInfo, oldPath)) => maybeDeleted - oldPath
      case _                                  => maybeDeleted

    def emitDeleted(deleted: Map[Path, FileInfo]): Stream[IO, Option[FileEvent]] =
      Stream.fromIterator(deleted.values.iterator.map(r => Some(FileDeleted(r))), 1)

    movedOrAdded.foldFlatMap(maybeDeleted)(filterMoved, emitDeleted).collect { case Some(e) => e}
  }

  private def applyEvent(state: Map[Path, FileInfo], e: FileEvent): Map[Path, FileInfo] = e match
    case FileAdded(fileInfo) =>
      state + (fileInfo.path -> fileInfo)
    case FileDeleted(fileInfo) =>
      state - fileInfo.path
    case FileMoved(fileInfo, oldPath) =>
      val prev = state(oldPath)
      state - oldPath + (fileInfo.path -> prev.copy(path = fileInfo.path)) // we keep the old metadata

  /**
   * Given an initial state, this method will poll the directory for changes and emit events for new, deleted and moved resources.
   * 
   * The state will be kept in memory and is not persisted.
   */
  def pollingStream(directoryPath: Path, initialState: Map[Path, FileInfo], pollInterval: FiniteDuration, pathFilter: Path => Boolean, hashFn: Path => IO[String]): Stream[IO, FileEvent] = {

    def unfoldRecursive(s: Map[Path, FileInfo]): Stream[IO, FileEvent] = {
      val startTime = System.currentTimeMillis()
      logger.debug(s"Scanning directory: ${directoryPath.toAbsolutePath}, previous state size: ${s.size}, stack depth: ${Thread.currentThread().getStackTrace.length}")
      
      def logTime = Stream.eval(IO { logger.debug(s"Scanning took: ${System.currentTimeMillis() - startTime} ms") })
      def sleep = Stream.sleep[IO](pollInterval)

      scanDirectory(directoryPath, s, pathFilter, hashFn)
        .foldFlatMap(s)(applyEvent, s => logTime >> sleep >> unfoldRecursive(s))
    }
    
    Stream.suspend(unfoldRecursive(initialState))
  }
}

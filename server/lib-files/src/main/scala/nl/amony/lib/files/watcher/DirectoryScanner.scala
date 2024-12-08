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
  private def scanDirectory(directory: Path, previous: FileStore, directoryFilter: Path => Boolean, fileFilter: Path => Boolean, hashFn: Path => IO[String]): Stream[IO, FileEvent] = {
    
    val start = System.currentTimeMillis()

    val currentFiles: Seq[(Path, BasicFileAttributes)] = RecursiveFileVisitor.listFilesInDirectoryRecursive(directory, directoryFilter, fileFilter)

    logger.debug(s"Listed directory files in ${System.currentTimeMillis() - start} ms")

    // new files are either added or moved
    val movedOrAdded: Stream[IO, Option[FileEvent]] = Stream.emits(currentFiles).evalMap { (path, attrs) =>

      for {
        prevByPath <- previous.getByPath(path)
        hash       <- prevByPath
                        .filter(_.equalFileMeta(path, attrs))
                        .map(i => IO.pure(i.hash))
                        .getOrElse(hashFn(path))
        fileInfo   = FileInfo(path, attrs, hash)
        event <- prevByPath match
                  case Some(prev) if prev.hash == fileInfo.hash => IO.pure(None) // file has the same path and hash
                  case _                =>
                    previous.getByHash(hash).map(_.filter(_.equalFileMeta(path, attrs)).headOption match {
                      case Some(oldFileInfo) => Some(FileMoved(fileInfo, oldFileInfo.path))
                      case _                 => Some(FileAdded(fileInfo))
                    })
      } yield event
    }

    // this is not correct, even if the path exists, the previous file might be deleted
    val maybeDeleted: Map[Path, FileInfo] = currentFiles.foldLeft(previous.getAll()){ case (acc, (p, _)) => acc - p }

    def filterMoved(maybeDeleted: Map[Path, FileInfo], e: Option[FileEvent]): Map[Path, FileInfo] = e match
      case None => maybeDeleted
      case Some(FileMoved(_, oldPath)) => maybeDeleted - oldPath
//      case Some(FileAdded(fileInfo)) if previousState.contains(fileInfo.path)  => maybeDeleted + (fileInfo.path -> previousState(fileInfo.path))
      case _                                  => maybeDeleted

    def emitDeleted(deleted: Map[Path, FileInfo]): Stream[IO, Option[FileEvent]] =
      Stream.fromIterator(deleted.values.iterator.map(r => Some(FileDeleted(r))), 1)

//    previous.getAll().fi
    
    movedOrAdded.foldFlatMap(maybeDeleted)(filterMoved, emitDeleted).collect { case Some(e) => e }
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
  def pollingStream(directoryPath: Path, initialState: Map[Path, FileInfo], pollInterval: FiniteDuration, directoryFilter: Path => Boolean, fileFilter: Path => Boolean, hashFn: Path => IO[String]): Stream[IO, FileEvent] = {

    def unfoldRecursive(s: Map[Path, FileInfo]): Stream[IO, FileEvent] = {
      val startTime = System.currentTimeMillis()
      logger.debug(s"Scanning directory: ${directoryPath.toAbsolutePath}, previous state size: ${s.size}, stack depth: ${Thread.currentThread().getStackTrace.length}")
      
      def logTime = Stream.eval(IO { logger.debug(s"Scanning took: ${System.currentTimeMillis() - startTime} ms") })
      def sleep = Stream.sleep[IO](pollInterval)

      val fileStore = new InMemoryFileStore(s)
      
      scanDirectory(directoryPath, fileStore, directoryFilter, fileFilter, hashFn)
        .foldFlatMap(s)(applyEvent, s => logTime >> sleep >> unfoldRecursive(s))
    }
    
    Stream.suspend(unfoldRecursive(initialState))
  }
}

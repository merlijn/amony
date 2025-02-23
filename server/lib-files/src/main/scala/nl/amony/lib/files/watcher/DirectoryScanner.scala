package nl.amony.lib.files.watcher

import cats.effect.IO
import fs2.Stream
import scribe.Logging

import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import scala.concurrent.duration.FiniteDuration

sealed trait FileEvent

case class FileMetaChanged(fileInfo: FileInfo) extends FileEvent
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

  def scanDirectory(directory: Path, previous: FileStore, directoryFilter: Path => Boolean, fileFilter: Path => Boolean, hashFunction: Path => IO[String]): Stream[IO, FileEvent] = {

    val currentFiles: Seq[(Path, BasicFileAttributes)] = RecursiveFileVisitor.listFilesInDirectoryRecursive(directory, directoryFilter, fileFilter)
    scanDirectory(currentFiles, previous, hashFunction)
  }

  /**
   * In principle, between the last time the directory was scanned and now, **all** files could be renamed. For example
   * using an automated renaming tool or script.
   * 
   * This means, the only thing we can rely on for checking equality is the metadata, not the filename.
   * 
   */
  def scanDirectory(currentFiles: Seq[(Path, BasicFileAttributes)],
                    previous: FileStore,
                    hashFunction: Path => IO[String]): Stream[IO, FileEvent] = {
    
    // new files are either added or moved
    def movedOrAdded(): Stream[IO, (Path, Option[FileEvent])] = Stream.emits(currentFiles).evalMap { (path, attrs) =>

      for {
        previousByPath <- previous.getByPath(path)
        equalMeta       = previousByPath.exists(_.equalFileMeta(attrs))
        hash           <- previousByPath
                           .filter(_ => equalMeta)
                           .map(i => IO.pure(i.hash))
                           .getOrElse(hashFunction(path))

        fileInfo   = FileInfo(path, attrs, hash)
        event <- previousByPath match
                  case Some(prev) if prev.hash == fileInfo.hash =>
                    if (equalMeta)
                      IO.pure(path -> None) // no change, the exact same file was found at the same path
                    else
                      /**
                       * A file with the same hash and name but different metadata was encountered.
                       * This may happen in case of backup recovery or moving files to/from different file systems.
                       */
                      IO.pure(path -> Some(FileMetaChanged(fileInfo)))
                  case _                =>
                    previous.getByHash(hash).map:
                      case previousByHash :: Nil if previousByHash.equalFileMeta(attrs) => path -> Some(FileMoved(fileInfo, previousByHash.path))
                      case previousByHash :: Nil                                        => path -> Some(FileMoved(fileInfo, previousByHash.path))
                      case Nil                                                          => path -> Some(FileAdded(fileInfo))
                      case _                                                            => path -> None
                    
      } yield event
    }

    // this is not correct, even if the path exists, the previous file might be deleted and replaced by another
//    val maybeDeleted: Map[Path, FileInfo] = currentFiles.foldLeft(previous.getAll()) { case (acc, (p, _)) => acc - p }

//    def filterMoved(maybeDeleted: Map[Path, FileInfo], e: Option[FileEvent]): Map[Path, FileInfo] = e match
//      case None => maybeDeleted
//      case Some(FileMoved(_, oldPath)) => maybeDeleted - oldPath
//      case Some(FileAdded(fileInfo)) if previousState.contains(fileInfo.path)  => maybeDeleted + (fileInfo.path -> previousState(fileInfo.path))
//      case _                           => maybeDeleted

//    def emitDeleted(deleted: Map[Path, FileInfo]): Stream[IO, Option[FileEvent]] =
//      Stream.fromIterator(deleted.values.iterator.map(r => Some(FileDeleted(r))), 1)

//    previous.getAll().flatMap { f => Stream.emit(Some(FileDeleted(f))) }.merge(movedOrAdded).map(_.map(Some(_)))
    
    movedOrAdded().collect { case (p, Some(e)) => e }
  }

  /**
   * Given an initial state, this method will poll the directory for changes and emit events for new, deleted and moved resources.
   * 
   * The state will be kept in memory and is not persisted.
   */
  def pollingStream(directoryPath: Path, fileStore: FileStore, pollInterval: FiniteDuration, directoryFilter: Path => Boolean, fileFilter: Path => Boolean, hashFn: Path => IO[String]): Stream[IO, FileEvent] = {

    def unfoldRecursive(fs: FileStore): Stream[IO, FileEvent] = {
      val startTime = System.currentTimeMillis()
      logger.debug(s"Scanning directory: ${directoryPath.toAbsolutePath}, previous state size: ${fs.size()}, stack depth: ${Thread.currentThread().getStackTrace.length}")
      
      def logTime = Stream.eval(IO { logger.debug(s"Scanning took: ${System.currentTimeMillis() - startTime} ms") })
      def sleep = Stream.sleep[IO](pollInterval)

      scanDirectory(directoryPath, fileStore, directoryFilter, fileFilter, hashFn)
        .foldFlatMap(fs)((old, e) => old.applyEvent(e), s => logTime >> sleep >> unfoldRecursive(fs))
    }
    
    Stream.suspend(unfoldRecursive(fileStore))
  }
}

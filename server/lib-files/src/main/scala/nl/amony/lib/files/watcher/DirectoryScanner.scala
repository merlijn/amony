package nl.amony.lib.files.watcher

import cats.effect.IO
import fs2.Stream
import scribe.Logging

import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import scala.concurrent.duration.FiniteDuration

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
    scanDirectory(Stream.emits(currentFiles), previous, hashFunction)
  }

  def populateStore(currentFiles: Stream[IO, (Path, BasicFileAttributes)],
                    snapshot: FileStore,
                    current: FileStore,
                    hashFunction: Path => IO[String]): IO[Unit] = {

    currentFiles.evalMap { (path, attrs) =>
      for {
        previousByPath <- snapshot.getByPath(path)
        equalMeta = previousByPath.exists(_.equalFileMeta(attrs))
        hash <- previousByPath
          .filter(_ => equalMeta)
          .map(i => IO.pure(i.hash))
          .getOrElse(hashFunction(path))
      } yield FileInfo(path, attrs, hash)
    }.evalMap(current.insert(_)).compile.drain
  }

  /**
   * In principle, between the last time the directory was scanned and now, **all** files could be renamed. For example
   * using an automated renaming tool or script.
   *
   * This means, the only thing we can rely on for checking equality is the metadata, not the filename.
   *
   */
  def scanDirectory2(current: FileStore,
                     previous: FileStore): Stream[IO, FileEvent] = {

    val movedOrAdded = current.getAll().evalMap { file =>

      previous.getByHash(file.hash).map {
        case Nil      => Some(FileAdded(file))
        case f :: Nil => 
          if (f.path == file.path) 
            if (f == file) 
              None
            else 
              Some(FileMetaChanged(file))
          else 
            Some(FileMoved(file, f.path))
        case multipleMatches       =>
          multipleMatches.find(_.path == file.path) match
            case Some(_) => None
            case None    => Some(FileMoved(file, multipleMatches.head.path))
      }
    }.collect { case Some(e) => e }
    
    val removed = previous.getAll().evalMap { file =>
      current.getByHash(file.hash).map {
        case Nil => Some(FileDeleted(file))
        case _   => None
      }
    }.collect { case Some(e) => e }
    
    movedOrAdded ++ removed
  }

  /**
   * In principle, between the last time the directory was scanned and now, **all** files could be renamed. For example
   * using an automated renaming tool or script.
   * 
   * This means, the only thing we can rely on for checking equality is the metadata, not the filename.
   * 
   */
  def scanDirectory(currentFiles: Stream[IO, (Path, BasicFileAttributes)],
                    fileStore: FileStore,
                    hashFunction: Path => IO[String]): Stream[IO, FileEvent] = {
    
    // new files are either added or moved
    def movedOrAdded(): Stream[IO, (Path, Option[FileEvent])] = currentFiles.evalMap { (path, attrs) =>

      for {
        previousByPath <- fileStore.getByPath(path)
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
                    fileStore.getByHash(hash).map:
                      case Nil                   => path -> Some(FileAdded(fileInfo))
                      case previousByHash :: Nil => path -> Some(FileMoved(fileInfo, previousByHash.path))
                      case _                     => path -> None
                    
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

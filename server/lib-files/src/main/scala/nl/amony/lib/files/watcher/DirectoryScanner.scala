package nl.amony.lib.files.watcher

import cats.effect.IO
import fs2.Stream
import scribe.Logging

import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import scala.concurrent.duration.FiniteDuration

object LocalDirectoryScanner extends Logging {

  def scanDirectory(directory: Path, previous: FileStore, directoryFilter: Path => Boolean, fileFilter: Path => Boolean, hashFunction: Path => IO[String]): Stream[IO, FileEvent] = {

    scanDirectory(
      RecursiveFileVisitor.streamFilesInDirectoryRecursive(directory, directoryFilter, fileFilter),
      previous,
      directoryFilter,
      fileFilter,
      hashFunction
    )
  }

  def scanDirectory(currentFiles: Stream[IO, (Path, BasicFileAttributes)], previous: FileStore, directoryFilter: Path => Boolean, fileFilter: Path => Boolean, hashFunction: Path => IO[String]): Stream[IO, FileEvent] = {

    val current = populateStore(
      currentFiles,
      previous,
      new InMemoryFileStore(),
      hashFunction
    )

    Stream.eval(current).flatMap(c => compareFileStores(previous, c))
  }

  private def populateStore(currentFiles: Stream[IO, (Path, BasicFileAttributes)],
                            previous: FileStore,
                            current: FileStore,
                            hashFunction: Path => IO[String]): IO[FileStore] = {

    currentFiles.evalMap { (path, attrs) =>
      for {
        previousByPath <- previous.getByPath(path)
        hash <- previousByPath
          .filter(_.equalFileMeta(attrs))
          .map(i => IO.pure(i.hash))
          .getOrElse(hashFunction(path))
      } yield FileInfo(path, attrs, hash)
    }.foreach(e => current.insert(e)).compile.drain >> IO.pure(current)
  }

  /**
   * In principle, between the last time the directory was scanned and now, **all** files could be renamed. For example
   * using an automated renaming tool or script.
   *
   * This means, the only thing we can rely on for checking equality is the metadata, not the filename.
   *
   */
  def compareFileStores(previous: FileStore, current: FileStore): Stream[IO, FileEvent] = {

    def maybeMetaChanged(file: FileInfo, previous: FileInfo): Option[FileEvent] =
      if (file == previous) None
      else Some(FileMetaChanged(file))

    val removed = previous.getAll().evalMap { file =>
      current.getByHash(file.hash).map {
        case Nil => Some(FileDeleted(file))
        case _ => None
      }
    }

    val movedOrAdded = current.getAll().evalMap { file =>

      previous.getByHash(file.hash).map {
        case Nil      => Some(FileAdded(file))
        case p :: Nil => 
          if (p.path == file.path)
            maybeMetaChanged(file, p)
          else 
            Some(FileMoved(file, p.path))
        case multipleMatches       =>
          multipleMatches.find(_.path == file.path) match
            case Some(p) => maybeMetaChanged(file, p)
            case None    => Some(FileMoved(file, multipleMatches.head.path)) // not correct
      }
    }

    (removed ++ movedOrAdded).collect { case Some(e) => e }
  }

  /**
   * Given an initial state, this method will poll the directory for changes and emit events for new, deleted and moved resources.
   * 
   * The state will be kept in memory and is not persisted.
   */
  def pollingStream(directory: Path, fileStore: FileStore, pollInterval: FiniteDuration, directoryFilter: Path => Boolean, fileFilter: Path => Boolean, hashFn: Path => IO[String]): Stream[IO, FileEvent] =
    pollingStream(() => RecursiveFileVisitor.streamFilesInDirectoryRecursive(directory, directoryFilter, fileFilter), fileStore, pollInterval, directoryFilter, fileFilter, hashFn)

  /**
   * Given an initial state, this method will poll the directory for changes and emit events for new, deleted and moved resources.
   *
   * The state will be kept in memory and is not persisted.
   */
  def pollingStream(scanFn: () => Stream[IO, (Path, BasicFileAttributes)], fileStore: FileStore, pollInterval: FiniteDuration, directoryFilter: Path => Boolean, fileFilter: Path => Boolean, hashFn: Path => IO[String]): Stream[IO, FileEvent] = {

    def unfoldRecursive(fs: FileStore): Stream[IO, FileEvent] = {
      val startTime = System.currentTimeMillis()
      logger.debug(s"Scanning, previous state size: ${fs.size()}, stack depth: ${Thread.currentThread().getStackTrace.length}")

      def logTime = Stream.eval(IO {
        logger.debug(s"Scanning took: ${System.currentTimeMillis() - startTime} ms")
      })

      def sleep = Stream.sleep[IO](pollInterval)

      scanDirectory(scanFn(), fileStore, directoryFilter, fileFilter, hashFn)
        .evalMap(e => fs.applyEvent(e).map(_ => e)) >> logTime >> sleep >> unfoldRecursive(fs)
    }

    Stream.suspend(unfoldRecursive(fileStore))
  }
}

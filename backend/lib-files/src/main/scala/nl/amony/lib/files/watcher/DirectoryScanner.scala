package nl.amony.lib.files.watcher

import cats.effect.IO
import fs2.Stream
import scribe.Logging

import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import scala.concurrent.duration.FiniteDuration

object LocalDirectoryScanner extends Logging {

  extension [F[_], T](stream: Stream[F, T]) {
    def foldFlatMap[S, E](initial: S)(foldFn: (S, T) => (S, Stream[F, E])): Stream[F, E] = {

      val f: Stream[F, (S, Stream[F, E])] = stream.scan[(S, Stream[F, E])](initial -> Stream.empty[F]):
        case ((acc, p), e) => foldFn(acc, e)

      f.flatMap:
        case (s, stream) => stream
    }
  }

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
          .filter(_.isSameFileMeta(attrs))
          .map(i => IO.pure(i.hash))
          .getOrElse(hashFunction(path))
      } yield FileInfo(path, attrs, hash)
    }.foreach(e => current.insert(e)).compile.drain >> IO.pure(current)
  }

  /**
   * Compares two file stores and emits events for added, deleted and moved files.
   * 
   * Delete events are guaranteed to be emitted before move or added events.
   * 
   * @param previous
   * @param current
   * @return
   */
  def compareFileStores(previous: FileStore, current: FileStore): Stream[IO, FileEvent] = {

    def maybeMetaChanged(file: FileInfo, previous: FileInfo): Option[FileEvent] =
      if (file == previous) None
      else Some(FileMetaChanged(file))

    val removed: Stream[IO, FileEvent] = previous.getAll().flatMap { file =>
      Stream.force(current.getByHash(file.hash).map {
        case Nil => Stream.emit(FileDeleted(file))
        case _   => Stream.empty
      })
    }

    val movedOrAdded: Stream[IO, FileEvent] = current.getAllByHash().foldFlatMap(Seq.empty[FileEvent]) {
      case (carriedEvents, (hash, files)) =>

        val events: IO[Stream[IO, FileEvent]] = previous.getByHash(hash).map { prev =>

          val filesA = prev.toSeq
          val filesB = files.toSeq

          val notMoved = Stream.emits(filesB.flatMap(b => filesA.find(_.path == b.path).map(_ -> b))
            .flatMap((a, b) => maybeMetaChanged(b, a)))

          val maybeDeleted = filesA.filterNot(f => filesB.exists(_.path == f.path))
          val maybeAdded   = filesB.filterNot(f => filesA.exists(_.path == f.path))

          val other = (maybeDeleted, maybeAdded) match {
            // file moved scenario
            case (a :: Nil, b :: Nil) => Stream.emit(FileMoved(b, a.path))
            // in other cases, we cannot determine if a file was moved, so we emit delete and add events
            case (a, b)               => Stream.emits(a.map(FileDeleted(_)) ++ b.map(FileAdded(_)))
          }

          notMoved ++ other
        }

        (carriedEvents, Stream.force(events))
      }

    removed ++ movedOrAdded
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

    def pollRecursive(fs: FileStore): Stream[IO, FileEvent] = {
      val startTime = System.currentTimeMillis()
      logger.debug(s"Scanning, previous state size: ${fs.size()}, stack depth: ${Thread.currentThread().getStackTrace.length}")

      def logTime = Stream.eval(IO {
        logger.debug(s"Scanning took: ${System.currentTimeMillis() - startTime} ms")
      })

      def sleep = Stream.sleep[IO](pollInterval)

      scanDirectory(scanFn(), fileStore, directoryFilter, fileFilter, hashFn)
        .evalMap(e => fs.applyEvent(e).map(_ => e)) >> logTime >> sleep >> pollRecursive(fs)
    }

    Stream.suspend(pollRecursive(fileStore))
  }
}

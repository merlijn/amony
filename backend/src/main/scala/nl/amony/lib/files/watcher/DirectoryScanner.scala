package nl.amony.lib.files.watcher

import java.io.IOException
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{FileVisitResult, Files, Path, SimpleFileVisitor}
import scala.concurrent.duration.FiniteDuration

import cats.effect.IO
import cats.effect.std.Queue
import fs2.Stream
import scribe.Logging

object LocalDirectoryScanner extends Logging {

  extension [F[_], T](stream: Stream[F, T]) {
    def foldFlatMap[S, E](initial: S)(foldFn: (S, T) => (S, Stream[F, E])): Stream[F, E] = {

      val f: Stream[F, (S, Stream[F, E])] = stream.scan[(S, Stream[F, E])](initial -> Stream.empty[F]):
        case ((acc, _), e) => foldFn(acc, e)

      f.flatMap:
        case (_, stream) => stream
    }
  }

  private def streamFilesInDirectoryRecursive(
    dir: Path,
    directoryFilter: Path => Boolean,
    fileFilter: Path => Boolean
  ): Stream[IO, (Path, BasicFileAttributes)] =

    Stream.eval(Queue.unbounded[IO, Option[(Path, BasicFileAttributes)]]).flatMap { queue =>

      val visitor = new SimpleFileVisitor[Path]:
        override def preVisitDirectory(d: Path, attrs: BasicFileAttributes): FileVisitResult =
          if directoryFilter(d) then FileVisitResult.CONTINUE else FileVisitResult.SKIP_SUBTREE

        override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult =
          if fileFilter(file) then queue.offer(Some((file, attrs))).unsafeRunSync()(using cats.effect.unsafe.IORuntime.global)
          FileVisitResult.CONTINUE

        override def visitFileFailed(file: Path, exc: IOException): FileVisitResult =
          logger.warn(s"Failed to visit path: $file")
          FileVisitResult.CONTINUE

      val walkTree = IO.blocking(Files.walkFileTree(dir,visitor)).guarantee(queue.offer(None)) // signal end of stream

      val dequeue = Stream.fromQueueNoneTerminated(queue)

      dequeue.concurrently(Stream.eval(walkTree))
    }

  def scanDirectory(
    directory: Path,
    previous: FileStore,
    directoryFilter: Path => Boolean,
    fileFilter: Path => Boolean,
    dedupIdFn: Path => IO[String]
  ): Stream[IO, FileEvent] =
    scanDirectory(
      streamFilesInDirectoryRecursive(directory, directoryFilter, fileFilter),
      previous,
      directoryFilter,
      fileFilter,
      dedupIdFn
    )

  def scanDirectory(
    currentFiles: Stream[IO, (Path, BasicFileAttributes)],
    previous: FileStore,
    directoryFilter: Path => Boolean,
    fileFilter: Path => Boolean,
    dedupIdFn: Path => IO[String]
  ): Stream[IO, FileEvent] = {

    val current = populateStore(currentFiles, previous, new InMemoryFileStore(), dedupIdFn)

    Stream.eval(current).flatMap(c => compareFileStores(previous, c))
  }

  private def populateStore(
    currentFiles: Stream[IO, (Path, BasicFileAttributes)],
    previous: FileStore,
    current: FileStore,
    dedupIdFn: Path => IO[String]
  ): IO[FileStore] = {

    currentFiles.evalMap { (path, attrs) =>
      for
        previousByPath <- previous.getByPath(path)
        dedupId        <- previousByPath
                            .filter(f => f.size == attrs.size && f.modifiedTime == attrs.lastModifiedTime().toMillis)
                            .map(i => IO.pure(i.dedupId)).getOrElse(dedupIdFn(path))
      yield FileInfo(path, attrs, dedupId)
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

    def maybeMetaChanged(file: FileInfo, previous: FileInfo): Option[FileEvent] = if file == previous then None else Some(FileMetaChanged(file))

    val removed: Stream[IO, FileEvent] = previous.getAll().flatMap { file =>
      Stream.force(current.getByDedupId(file.dedupId).map {
        case Nil => Stream.emit(FileDeleted(file))
        case _   => Stream.empty
      })
    }

    val movedOrAdded: Stream[IO, FileEvent] = current.getAllDedupId().foldFlatMap(Seq.empty[FileEvent]) {
      case (carriedEvents, (dedupId, files)) =>

        val events: IO[Stream[IO, FileEvent]] = previous.getByDedupId(dedupId).map { prev =>

          val bucketA = prev.toSeq
          val bucketB = files.toSeq

          val notMoved     = Stream.emits(bucketB.flatMap(b => bucketA.find(_.path == b.path).map(_ -> b)).flatMap((a, b) => maybeMetaChanged(b, a)))
          val maybeDeleted = bucketA.filterNot(f => bucketB.exists(_.path == f.path))
          val maybeAdded   = bucketB.filterNot(f => bucketA.exists(_.path == f.path))

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
  def pollingStream(
    directory: Path,
    fileStore: FileStore,
    pollInterval: FiniteDuration,
    directoryFilter: Path => Boolean,
    fileFilter: Path => Boolean,
    dedupIdFn: Path => IO[String]
  ): Stream[IO, FileEvent] =
    pollingStream(
      () => streamFilesInDirectoryRecursive(directory, directoryFilter, fileFilter),
      fileStore,
      pollInterval,
      directoryFilter,
      fileFilter,
      dedupIdFn
    )

  /**
   * Given an initial state, this method will poll the directory for changes and emit events for new, deleted and moved resources.
   *
   * The state will be kept in memory and is not persisted.
   */
  def pollingStream(
    scanFn: () => Stream[IO, (Path, BasicFileAttributes)],
    fileStore: FileStore,
    pollInterval: FiniteDuration,
    directoryFilter: Path => Boolean,
    fileFilter: Path => Boolean,
    dedupIdFn: Path => IO[String]
  ): Stream[IO, FileEvent] = {

    def pollRecursive(fs: FileStore): Stream[IO, FileEvent] = {
      val startTime = System.currentTimeMillis()
      logger.debug(s"Scanning, previous state size: ${fs.size()}, stack depth: ${Thread.currentThread().getStackTrace.length}")

      def logTime = Stream.eval(IO(logger.debug(s"Scanning took: ${System.currentTimeMillis() - startTime} ms")))
      def sleep   = Stream.sleep[IO](pollInterval)

      scanDirectory(scanFn(), fileStore, directoryFilter, fileFilter, dedupIdFn).evalMap(e => fs.applyEvent(e).map(_ => e)) >> logTime >> sleep >>
        pollRecursive(fs)
    }

    Stream.suspend(pollRecursive(fileStore))
  }
}

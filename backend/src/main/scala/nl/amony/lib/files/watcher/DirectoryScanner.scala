package nl.amony.lib.files.watcher

import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import scala.concurrent.duration.FiniteDuration

import cats.effect.IO
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

  def scanDirectory(
    directory: Path,
    previous: FileStore,
    directoryFilter: Path => Boolean,
    fileFilter: Path => Boolean,
    partialHashFunction: Path => IO[String],
    parallelism: Int
  ): Stream[IO, FileEvent] = {

    scanDirectory(
      RecursiveFileVisitor.streamFilesInDirectoryRecursive(directory, directoryFilter, fileFilter),
      previous,
      directoryFilter,
      fileFilter,
      partialHashFunction,
      parallelism
    )
  }

  def scanDirectory(
    currentFiles: Stream[IO, (Path, BasicFileAttributes)],
    previous: FileStore,
    directoryFilter: Path => Boolean,
    fileFilter: Path => Boolean,
    partialHashFunction: Path => IO[String],
    parallelism: Int
  ): Stream[IO, FileEvent] = {

    val current = populateStore(currentFiles, previous, new InMemoryFileStore(), partialHashFunction, parallelism)

    Stream.eval(current).flatMap(c => compareFileStores(previous, c))
  }

  private def populateStore(
    currentFiles: Stream[IO, (Path, BasicFileAttributes)],
    previous: FileStore,
    current: FileStore,
    partialHashFunction: Path => IO[String],
    parallelism: Int
  ): IO[FileStore] = {

    currentFiles.parEvalMapUnordered(parallelism) { (path, attrs) =>
      val size         = attrs.size()
      val modifiedTime = attrs.lastModifiedTime().toMillis
      val fileKey      = Option(attrs.fileKey()).map(_.toString)

      // same path with same size and modifiedTime normally means unchanged content,
      // but a changed fileKey (e.g. replaced via cp -p) proves otherwise and forces a rehash
      def keysCompatible(cached: FileInfo): Boolean = (cached.fileKey, fileKey) match
        case (Some(previousKey), Some(currentKey)) => previousKey == currentKey
        case _                                     => true

      def cachedByPath: IO[Option[String]] =
        previous.getByPath(path).map(_.filter(f => f.size == size && f.modifiedTime == modifiedTime && keysCompatible(f)).map(_.partialHash))

      // renames keep size, modifiedTime and fileKey, so a previous entry with the same triple lets us skip hashing
      def cachedByFileKey: IO[Option[String]] = fileKey match
        case None      => IO.pure(None)
        case Some(key) =>
          previous.getByFileKey(key).map(_.find(f => f.size == size && f.modifiedTime == modifiedTime).map(_.partialHash))

      for
        byPath      <- cachedByPath
        byFileKey   <- byPath match
                         case Some(_) => IO.pure(None)
                         case None    => cachedByFileKey
        partialHash <- (byPath orElse byFileKey).map(IO.pure).getOrElse(partialHashFunction(path))
      yield FileInfo(path, attrs, partialHash)
    }.foreach(e => current.insert(e)).compile.drain >> IO.pure(current)
  }

  /**
   * Compares two file stores and emits events for added, deleted, moved and content-changed files.
   *
   * Delete events are guaranteed to be emitted before move or added events.
   *
   * Content changes are detected first by path: a same-path pair with equal fileKeys but different
   * content fields means the file was edited in place. Anything else falls through to the
   * hash-bucket matching below.
   *
   * @param previous
   * @param current
   * @return
   */
  def compareFileStores(previous: FileStore, current: FileStore): Stream[IO, FileEvent] = {

    def sameFileKey(a: FileInfo, b: FileInfo): Boolean = (a.fileKey, b.fileKey) match
      case (Some(x), Some(y)) => x == y
      case _                  => false

    def maybeMetaChanged(file: FileInfo, previous: FileInfo): Option[FileEvent] = if file == previous then None else Some(FileMetaChanged(file))

    def samePathPass(): IO[(Set[Path], List[FileEvent])] =
      current.getAll().evalMap { file =>
        previous.getByPath(file.path).map {
          case Some(prev) if file == prev            => (Some(file.path), None)
          case Some(prev) if sameFileKey(file, prev) => (Some(file.path), Some(FileContentChanged(file)))
          case _                                     => (None, None)
        }
      }.compile.toList.map { list =>
        (list.collect { case (Some(path), _) => path }.toSet, list.collect { case (_, Some(event)) => event })
      }

    def removed(consumed: Set[Path]): Stream[IO, FileEvent] =
      previous.getAll().filter(file => !consumed(file.path)).flatMap { file =>
        Stream.force(current.getByPartialHash(file.partialHash).map {
          case Nil => Stream.emit(FileDeleted(file))
          case _   => Stream.empty
        })
      }

    def movedOrAdded(consumed: Set[Path]): Stream[IO, FileEvent] = current.getAllByPartialHash().foldFlatMap(Seq.empty[FileEvent]) {
      case (carriedEvents, (partialHash, files)) =>

        val events: IO[Stream[IO, FileEvent]] = previous.getByPartialHash(partialHash).map { prev =>

          val filesA = prev.filterNot(f => consumed(f.path)).toSeq
          val filesB = files.filterNot(f => consumed(f.path)).toSeq

          val notMoved = Stream.emits(filesB.flatMap(b => filesA.find(_.path == b.path).map(_ -> b)).flatMap((a, b) => maybeMetaChanged(b, a)))

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

    Stream.eval(samePathPass()).flatMap { (consumed, contentChanged) =>
      Stream.emits(contentChanged) ++ removed(consumed) ++ movedOrAdded(consumed)
    }
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
    partialHashFn: Path => IO[String],
    parallelism: Int
  ): Stream[IO, FileEvent] = pollingStream(
    () => RecursiveFileVisitor.streamFilesInDirectoryRecursive(directory, directoryFilter, fileFilter),
    fileStore,
    pollInterval,
    directoryFilter,
    fileFilter,
    partialHashFn,
    parallelism
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
    partialHashFn: Path => IO[String],
    parallelism: Int
  ): Stream[IO, FileEvent] = {

    def pollRecursive(fs: FileStore): Stream[IO, FileEvent] = {
      val startTime = System.currentTimeMillis()
      logger.debug(s"Scanning, previous state size: ${fs.size()}, stack depth: ${Thread.currentThread().getStackTrace.length}")

      def logTime = Stream.eval(IO(logger.debug(s"Scanning took: ${System.currentTimeMillis() - startTime} ms")))

      def sleep = Stream.sleep[IO](pollInterval)

      scanDirectory(scanFn(), fileStore, directoryFilter, fileFilter, partialHashFn, parallelism).evalMap(e =>
        fs.applyEvent(e).map(_ => e)
      ) >> logTime >> sleep >>
        pollRecursive(fs)
    }

    Stream.suspend(pollRecursive(fileStore))
  }
}

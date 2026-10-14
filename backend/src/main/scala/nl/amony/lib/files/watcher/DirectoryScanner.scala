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
   * Compares two file stores and emits events for added, deleted, moved, modified and
   * moved-and-modified files.
   *
   * The comparison streams through the stores and never holds more than one hash bucket plus a
   * bounded pairing set in memory, so FileStore implementations backed by a database stay viable:
   * beyond the streams themselves only indexed point lookups are used, and only in rare branches
   * (vanished hashes, 1-1 moves, key pairing).
   *
   * Content changes are detected first by path: a same-path pair with equal fileKeys but different
   * content fields means the file was edited in place. Unmatched deletes and adds sharing a fileKey
   * are then paired into moves with modification, but only for unambiguous 1-1 key matches.
   *
   * Modified events come first, then deletes intermixed with paired moves, then the remaining
   * resource events.
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

    def samePathPass(): IO[List[FileEvent]] =
      current.getAll().evalMap { file =>
        previous.getByPath(file.path).map {
          case Some(prev) if file != prev && sameFileKey(file, prev) => Some(FileModified(file))
          case _                                                     => None
        }
      }.collect { case Some(event) => event }.compile.toList

    // True when the same-path pair was already reported by the same-path pass (silent when
    // equal, FileModified otherwise), so the passes below must stay silent for it.
    def handledBySamePathPass(file: FileInfo, counterpart: Option[FileInfo]): Boolean =
      counterpart.exists(other => file == other || sameFileKey(file, other))

    // Deletes, pairing same-key additions on the fly (a move with modification). Returns the
    // paired paths alongside the events: the set is bounded by the number of paired moves and
    // is complete before movedOrAdded runs below, which skips those paths.
    def removed(): IO[(Set[Path], List[FileEvent])] =
      previous.getAll().evalMap { file =>
        current.getByPartialHash(file.partialHash).flatMap {
          case Nil => deletedOrPaired(file)
          case _   => IO.pure((Set.empty[Path], List.empty[FileEvent]))
        }
      }.compile.toList.map(_.foldLeft((Set.empty[Path], List.empty[FileEvent])) {
        case ((paths, events), (p, e)) => (paths ++ p, events ++ e)
      })

    def deletedOrPaired(file: FileInfo): IO[(Set[Path], List[FileEvent])] =
      current.getByPath(file.path).flatMap { counterpart =>
        if handledBySamePathPass(file, counterpart) then IO.pure((Set.empty[Path], List.empty[FileEvent]))
        else
          file.fileKey match
            case None      => IO.pure((Set.empty[Path], List(FileDeleted(file))))
            case Some(key) =>
              for
                previousKeyCount <- previous.getByFileKey(key).map(_.size)
                partner          <- current.getByFileKey(key).map(_.filter(_.path != file.path))
                result           <- (previousKeyCount, partner) match {
                                      case (1, next :: Nil) => IO.pure((Set(file.path, next.path), List(FileMovedAndModified(next, file.path))))
                                      case _                => IO.pure((Set.empty[Path], List(FileDeleted(file))))
                                    }
              yield result
      }

    def movedOrAdded(paired: Set[Path]): Stream[IO, FileEvent] = current.getAllByPartialHash().foldFlatMap(Seq.empty[FileEvent]) {
      case (carriedEvents, (partialHash, files)) =>

        val events: IO[Stream[IO, FileEvent]] = previous.getByPartialHash(partialHash).flatMap { prev0 =>

          val prev = prev0.filterNot(f => paired(f.path))
          val curr = files.filterNot(f => paired(f.path)).toSeq

          val notMoved = Stream.emits(
            curr.flatMap(b => prev.find(_.path == b.path).map(a => (a, b))).flatMap((a, b) =>
              if handledBySamePathPass(b, Some(a)) then None else maybeMetaChanged(b, a)
            )
          )

          def dropConsumed(files: List[FileInfo], counterpartOf: Path => IO[Option[FileInfo]]): IO[List[FileInfo]] =
            Stream.emits(files).evalFilter(f => counterpartOf(f.path).map(c => !handledBySamePathPass(f, c))).compile.toList

          val maybeDeleted = prev.filterNot(f => curr.exists(_.path == f.path)).toList
          val maybeAdded   = curr.filterNot(f => prev.exists(_.path == f.path)).toList

          val other: IO[Stream[IO, FileEvent]] = (maybeDeleted, maybeAdded) match {
            // file moved scenario, unless either side was already reported by the same-path pass
            case (a :: Nil, b :: Nil) =>
              for
                aConsumed <- current.getByPath(a.path).map(handledBySamePathPass(a, _))
                bConsumed <- previous.getByPath(b.path).map(handledBySamePathPass(b, _))
              yield (aConsumed, bConsumed) match {
                case (true, true)   => Stream.empty
                case (true, false)  => Stream.emit(FileAdded(b))
                case (false, true)  => Stream.emit(FileDeleted(a))
                case (false, false) => Stream.emit(FileMoved(b, a.path))
              }
            // in other cases, we cannot determine if a file was moved, so we emit delete and add events
            case (deleted, added)     =>
              for
                keptDeleted <- dropConsumed(deleted, current.getByPath)
                keptAdded   <- dropConsumed(added, previous.getByPath)
              yield Stream.emits(keptDeleted.map(FileDeleted(_)) ++ keptAdded.map(FileAdded(_)))
          }

          other.map(notMoved ++ _)
        }

        (carriedEvents, Stream.force(events))
    }

    Stream.eval(samePathPass()).flatMap { modified =>
      Stream.emits(modified) ++
        Stream.eval(removed()).flatMap { (paired, deleted) =>
          Stream.emits(deleted) ++ movedOrAdded(paired)
        }
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

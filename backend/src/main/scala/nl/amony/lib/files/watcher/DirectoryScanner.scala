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
   * Compares two file stores and emits events for added, deleted, moved, content-changed and
   * moved-and-modified files.
   *
   * Delete events are guaranteed to be emitted before move or added events.
   *
   * Content changes are detected first by path: a same-path pair with equal fileKeys but different
   * content fields means the file was edited in place. Anything else falls through to the
   * hash-bucket matching below. Unmatched deletes and adds sharing a fileKey are paired into
   * moves with modification, but only for unambiguous 1-1 key matches.
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
          case Some(prev) if sameFileKey(file, prev) => (Some(file.path), Some(FileModified(file)))
          case _                                     => (None, None)
        }
      }.compile.toList.map { list =>
        (list.collect { case (Some(path), _) => path }.toSet, list.collect { case (_, Some(event)) => event })
      }

    def hashBucketPass(consumed: Set[Path]): IO[(List[FileEvent], List[FileInfo], List[FileInfo])] =
      for
        prevGroups <- previous.getAll().filterNot(file => consumed(file.path)).compile.toList.map(_.groupBy(_.partialHash))
        currGroups <- current.getAllByPartialHash().compile.toList.map(
                        _.map((hash, files) => (hash, files.filterNot(file => consumed(file.path)).toList)).filter(_._2.nonEmpty).toMap
                      )
      yield (prevGroups.keySet ++ currGroups.keySet).toList.map { hash =>
        val (prev, curr): (List[FileInfo], List[FileInfo]) = (prevGroups.getOrElse(hash, Nil), currGroups.getOrElse(hash, Nil))
        (prev, curr) match {
          case (Nil, added)   => (List.empty[FileEvent], List.empty[FileInfo], added)
          case (deleted, Nil) => (List.empty[FileEvent], deleted, List.empty[FileInfo])
          case _              =>
            val notMoved     = curr.flatMap(b => prev.find(_.path == b.path).map(_ -> b)).flatMap((a, b) => maybeMetaChanged(b, a))
            val maybeDeleted = prev.filterNot(f => curr.exists(_.path == f.path))
            val maybeAdded   = curr.filterNot(f => prev.exists(_.path == f.path))

            (maybeDeleted, maybeAdded) match {
              // file moved scenario
              case (a :: Nil, b :: Nil) => (notMoved :+ FileMoved(b, a.path), List.empty[FileInfo], List.empty[FileInfo])
              // in other cases, we cannot determine if a file was moved, so we emit delete and add events
              case (deleted, added)     => (notMoved, deleted, added)
            }
        }: (List[FileEvent], List[FileInfo], List[FileInfo])
      }.foldLeft((List.empty[FileEvent], List.empty[FileInfo], List.empty[FileInfo])) {
        case ((events, deleted, added), (e, d, a)) => (events ++ e, deleted ++ d, added ++ a)
      }

    // pairs unmatched deletes and adds sharing a fileKey (a move with modification),
    // but only for unambiguous 1-1 key matches
    def pairByKey(
      deleted: List[FileInfo],
      added: List[FileInfo]
    ): (List[FileEvent], List[FileInfo], List[FileInfo]) = {
      val deletedByKey = deleted.filter(_.fileKey.isDefined).groupBy(_.fileKey)
      val addedByKey   = added.filter(_.fileKey.isDefined).groupBy(_.fileKey)

      val pairedKeys = (deletedByKey.keySet intersect addedByKey.keySet).filter { key =>
        deletedByKey(key).size == 1 && addedByKey(key).size == 1
      }

      val paired      = pairedKeys.toList.map(key => FileMovedAndModified(addedByKey(key).head, deletedByKey(key).head.path))
      val pairedPaths = pairedKeys.flatMap(key => List(deletedByKey(key).head.path, addedByKey(key).head.path)).toSet

      (paired, deleted.filterNot(f => pairedPaths(f.path)), added.filterNot(f => pairedPaths(f.path)))
    }

    Stream.eval {
      for
        (consumed, contentChanged)        <- samePathPass()
        (pure, deleted, added)            <- hashBucketPass(consumed)
        (paired, finalDeleted, finalAdded) = pairByKey(deleted, added)
      yield contentChanged ++ finalDeleted.map(FileDeleted(_)) ++ pure ++ paired ++ finalAdded.map(FileAdded(_))
    }.flatMap(Stream.emits)
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

package nl.amony.service.resources.local.scanner

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import fs2.Stream
import nl.amony.service.resources.ResourceConfig.LocalDirectoryConfig
import nl.amony.service.resources.Resource
import nl.amony.service.resources.api.events.*
import nl.amony.service.resources.api.operations.ResourceOperation
import nl.amony.service.resources.api.{ResourceInfo, ResourceMeta}
import nl.amony.service.resources.local.LocalResourceMeta
import nl.amony.service.resources.local.db.LocalDirectoryDb
import scribe.Logging
import slick.jdbc.JdbcProfile

import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{Files, Path}
import scala.concurrent.duration.FiniteDuration

case class FileInfo(path: Path, hash: String, size: Long, creationTime: Long, modifiedTime: Long) {
  def equalFileMeta(path: Path): Boolean = {
    val attrs = Files.readAttributes(path, classOf[BasicFileAttributes])
    size == Files.size(path) && creationTime == attrs.creationTime().toMillis && modifiedTime == Files.getLastModifiedTime(path).toMillis
  }
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

class LocalDirectoryScanner(config: LocalDirectoryConfig)(implicit runtime: IORuntime) extends Logging {

  private val hashingAlgorithm = config.hashingAlgorithm
  private val mediaPath = config.resourcePath

  /**
   * In principle, between the last time the directory was scanned and now, **all** files could be renamed. For example
   * using an automated renaming tool or script.
   * 
   * This means, the only thing we can rely on for checking equality is the metadata.
   * 
   */
  private def scanDirectory(previousState: Set[FileInfo]): Stream[IO, FileEvent] = {
    
    def filterPath(path: Path) = config.filterFileName(path.getFileName.toString)

    logger.debug(s"Scanning directory: ${mediaPath.toAbsolutePath}, previous state size: ${previousState.size}, last modified: ${Files.getLastModifiedTime(mediaPath).toInstant}")

    val previousByPath: Map[Path, FileInfo]   = previousState.foldLeft(Map.empty)( (acc, f) => acc + (f.path -> f) )
    val previousByHash: Map[String, FileInfo] = previousState.foldLeft(Map.empty)( (acc, f) => acc + (f.hash -> f) )

    def getByPath(path: Path): Option[FileInfo]   = previousByPath.get(path)
    def getByHash(hash: String): Option[FileInfo] = previousByHash.get(hash)

    def currentFiles  = RecursiveFileVisitor.listFilesInDirectoryRecursive(mediaPath, filterPath).toSet
    val previousFiles = previousState.map(f => f.path)

    // new files are either added or moved
    val movedOrAdded = Stream.emits(currentFiles.toSeq).evalMap { path =>

      val prevByPath: Option[FileInfo] = getByPath(path)

      // if the metadata is equal to the previous file, we assume it was unchanged and re-use the hash
      def calculateHash(): IO[String] =
        prevByPath
          .filter(_.equalFileMeta(path))
          .map(i => IO.pure(i.hash))
          .getOrElse(hashingAlgorithm.createHash(path))

      for {
        hash       <- calculateHash()
        fileInfo   = FileInfo(
          path         = path,
          hash         = hash,
          size         = Files.size(path),
          creationTime = Files.readAttributes(path, classOf[BasicFileAttributes]).creationTime().toMillis,
          modifiedTime = Files.getLastModifiedTime(path).toMillis
        )
      } yield
        prevByPath match {
          case Some(`fileInfo`) => None
          case None             =>
            getByHash(hash) match {
              case Some(oldFileInfo) if oldFileInfo.equalFileMeta(path) =>
                Some(FileMoved(fileInfo, oldFileInfo.path))
              case _              =>
                Some(FileAdded(fileInfo))
            }
        }
    }

    def filterMoved(maybeDeleted: Map[Path, FileInfo], e: Option[FileEvent]): Map[Path, FileInfo] = e match
      case Some(FileMoved(fileInfo, oldPath)) => maybeDeleted.removed(oldPath)
      case _                                  => maybeDeleted

    // removed files might be deleted or moved
    val maybeDeleted = previousByPath.view.filterKeys(!currentFiles.contains(_)).toMap

    def emitDeleted(deleted: Map[Path, FileInfo]): Stream[IO, Option[FileEvent]] =
      Stream.fromIterator(deleted.values.iterator.map(r => Some(FileDeleted(r))), 1)

    movedOrAdded.foldFlatMap(maybeDeleted)(filterMoved, emitDeleted).collect { case Some(e) => e}
  }

  private def applyEvent(state: Set[FileInfo], e: FileEvent): Set[FileInfo] = e match {
    case FileAdded(fileInfo) =>
      state + fileInfo
    case FileDeleted(fileInfo) =>
      state.filterNot(_.path == fileInfo.path)
    case FileMoved(fileInfo, oldPath) =>
      state.map { r =>
        if (r.path == oldPath) r.copy(path = fileInfo.path)
        else r
      }
  }

  /**
   * Given an initial state, this method will poll the directory for changes and emit events for new, deleted and moved resources.
   * 
   * The state will be kept in memory and is not persisted.
   */
  def pollingStream(initialState: Set[FileInfo], pollInterval: FiniteDuration): Stream[IO, FileEvent] = {

    def unfoldRecursive(s: Set[FileInfo]): Stream[IO, FileEvent] = {
      val startTime = System.currentTimeMillis()
      logger.debug(s"Scanning directory: ${mediaPath.toAbsolutePath}")
      def logTime = Stream.eval(IO { logger.debug(s"Scanning took: ${System.currentTimeMillis() - startTime} ms") })
      scanDirectory(s).foldFlatMap(s)(applyEvent, s => logTime >> Stream.sleep[IO](pollInterval) >> unfoldRecursive(s))
    }

    Stream.suspend(unfoldRecursive(initialState))
  }

  /**
   * Given an initial state, this method will poll the directory for changes and emit events for new, deleted and moved resources.
   *
   * The state will be kept in memory and is not persisted.
   */
  def pollingResourceEventStream(initialState: Set[ResourceInfo], pollInterval: FiniteDuration): Stream[IO, ResourceEvent] = {

    val initialFiles = initialState.map { r =>
      FileInfo(mediaPath.resolve(Path.of(r.path)), r.hash, r.size, r.creationTime.getOrElse(0), r.lastModifiedTime.getOrElse(0))
    }

    pollingStream(initialFiles, pollInterval).parEvalMap(4) {
      case FileAdded(f) =>

        val resourceMeta: IO[ResourceMeta] =
          LocalResourceMeta.resolveMeta(f.path)
            .map(_.getOrElse(ResourceMeta.Empty))
            .recover {
              case e => logger.error(s"Failed to resolve meta for ${f.path}", e); ResourceMeta.Empty
            }

        for {
          meta <- resourceMeta
        } yield ResourceAdded(ResourceInfo(
          bucketId = config.id,
          path = mediaPath.relativize(f.path).toString,
          hash = f.hash,
          size = f.size,
          contentType = Resource.contentTypeForPath(f.path),
          contentMeta = meta,
          creationTime = Some(f.creationTime),
          lastModifiedTime = Some(f.modifiedTime)
        ))

      case FileDeleted(f) =>
        IO(ResourceDeleted(f.hash))

      case FileMoved(f, oldPath) =>
        IO(ResourceMoved(f.hash, mediaPath.relativize(oldPath).toString, mediaPath.relativize(f.path).toString))
    }
  }
}

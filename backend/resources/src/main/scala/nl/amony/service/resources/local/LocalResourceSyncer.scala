package nl.amony.service.resources.local

import cats.effect.IO
import fs2.concurrent.SignallingRef
import fs2.{Pipe, Stream}
import nl.amony.lib.files.watcher.*
import nl.amony.lib.messagebus.EventTopic
import nl.amony.service.resources.Resource
import nl.amony.service.resources.ResourceConfig.LocalDirectoryConfig
import nl.amony.service.resources.api.*
import nl.amony.service.resources.api.events.*
import nl.amony.service.resources.database.ResourceDatabase

import java.nio.file.{Files, Path}

/**
 * Functionality to synchronize a local directory with the database state.
 */
class LocalResourceSyncer(db: ResourceDatabase, config: LocalDirectoryConfig, topic: EventTopic[ResourceEvent]) {

  private val logger = scribe.Logger("LocalResourceSyncer")

  private val bucketId = config.id

  private def relativizePath(path: Path): String = config.resourcePath.relativize(path).toString

  private def mapFileEvent(fileEvent: FileEvent): IO[ResourceEvent] = {

    def withRequireResource(hash: String, path: Path)(fn: ResourceInfo => ResourceEvent): IO[ResourceEvent] =
      db.getByHash(bucketId, hash)
        .map(_.find(_.path == relativizePath(path)))
        .flatMap:
          case None    => IO.raiseError(new IllegalStateException("No such file in database"))
          case Some(r) => IO.pure(fn(r))

    fileEvent match {

      case FileMetaChanged(f) =>
        withRequireResource(f.hash, f.path)(r => ResourceFileMetaChanged(r.resourceId, Some(f.creationTime), Some(f.modifiedTime)))

      case FileDeleted(f) =>
        withRequireResource(f.hash, f.path)(r => ResourceDeleted(r.resourceId))

      case FileAdded(f) =>
        asNewResource(f, "0").map(ResourceAdded(_))

      case FileMoved(file, oldFilePath) =>
        val newPath = relativizePath(file.path)
        val oldPath = relativizePath(oldFilePath)

        withRequireResource(file.hash, oldFilePath)(r => ResourceMoved(r.resourceId, oldPath, newPath))
    }
  }
  
  private[local] def asNewResource(f: FileInfo, userId: String): IO[ResourceInfo] = {
    LocalResourceMeta.detectMetaData(f.path)
      .recover { case e => logger.error(s"Failed to resolve meta for ${f.path}", e); None }
      .map { meta =>
        ResourceInfo(
          bucketId = bucketId,
          resourceId = config.generateId(),
          userId = userId,
          path = relativizePath(f.path),
          hash = Some(f.hash),
          size = f.size,
          contentType = meta.map(_.contentType),
          contentMetaSource = meta.flatMap(_.toolMeta),
          contentMeta = meta.map(_.meta).getOrElse(ResourceMeta.Empty),
          creationTime = Some(f.creationTime),
          lastModifiedTime = Some(f.modifiedTime),
          thumbnailTimestamp = None
        )
      }
  }

  private def toFileStore(): IO[FileStore] =
    db.getAll(bucketId).map { resources =>
      val initialFiles: Seq[FileInfo] = resources.map { r => FileInfo(config.resourcePath.resolve(Path.of(r.path)), r.hash.get, r.size, r.creationTime.getOrElse(0), r.lastModifiedTime.getOrElse(0)) }
      InMemoryFileStore(initialFiles)
    }

  /**
   * Given an initial state, this method will poll the directory for changes and emit events for new, deleted and moved resources.
   *
   * The state will be kept in memory and is not persisted.
   */
  private def singleScan(): Stream[IO, ResourceEvent] =
    logger.info(s"Scanning directory: ${config.resourcePath}")
    Stream.eval(toFileStore()).flatMap: fileStore =>
      LocalDirectoryScanner.scanDirectory(config.resourcePath, fileStore, config.filterDirectory, config.filterFiles, config.scan.hashingAlgorithm.createHash)
        .parEvalMap(config.scan.scanParallelFactor)(mapFileEvent)

  /**
   * Given an initial state, this method will poll the directory for changes and emit events for new, deleted and moved resources.
   *
   * The state will be kept in memory and is not persisted.
   */
  private def pollingResourceEventStream(): Stream[IO, ResourceEvent] =
    Stream.eval(toFileStore()).flatMap: fileStore =>
      LocalDirectoryScanner
        .pollingStream(config.resourcePath, fileStore, config.scan.pollInterval, config.filterDirectory, config.filterFiles, config.scan.hashingAlgorithm.createHash)
        .parEvalMap(config.scan.scanParallelFactor)(mapFileEvent)

  private def applyEventToDb(event: ResourceEvent): IO[Unit] =
    event match {
      case ResourceAdded(resource) => db.insertResource(resource)
      case ResourceDeleted(resourceId) => db.deleteResource(config.id, resourceId)
      case ResourceMoved(id, _, newPath) => db.move(config.id, id, newPath)
      case ResourceFileMetaChanged(id, creationTime, lastModifiedTime) =>
        db.getById(config.id, id).flatMap {
          case Some(resource) => db.upsert(resource.copy(creationTime = creationTime, lastModifiedTime = lastModifiedTime))
          case None => IO.unit
        }
      case _ => IO.unit
    }

  private val processEvent: Pipe[IO, ResourceEvent, ResourceEvent]     =
    _ evalTap (e => applyEventToDb(e) >> topic.publish(e) >> IO(logger.info(s"Resource event: $e")))

  private def startSync(interrupter: SignallingRef[IO, Boolean]): IO[Unit] = {
    def pollWithRetryOnException(): fs2.Stream[IO, ResourceEvent] =
      pollingResourceEventStream()
        .through(processEvent)
        .interruptWhen(interrupter)
        .handleErrorWith { e =>
          logger.error(s"Error while scanning directory ${config.resourcePath.toAbsolutePath}, retrying in ${config.scan.pollInterval}", e)
          fs2.Stream.sleep[IO](config.scan.pollInterval) >> pollWithRetryOnException()
        }

    logger.info(s"Starting polling at interval ${config.scan.pollInterval} for: ${config.resourcePath.toAbsolutePath}")

    pollWithRetryOnException().compile.drain
  }

  def refresh() =
    singleScan()
      .through(processEvent)
      .compile
      .drain

  def sync(): IO[Unit] = {
    if (!config.scan.enabled)
      IO.unit
    else
      SignallingRef[IO, Boolean](false).flatMap(signal => startSync(signal))
  }
}

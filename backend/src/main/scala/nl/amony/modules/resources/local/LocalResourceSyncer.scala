package nl.amony.modules.resources.local

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

import cats.effect.IO
import fs2.Stream
import fs2.concurrent.SignallingRef

import nl.amony.lib.files.watcher.*
import nl.amony.modules.auth.api.UserId
import nl.amony.modules.resources.api.*
import nl.amony.modules.resources.api.{ResourceAdded, ResourceDeleted, ResourceEvent, ResourceFileMetaChanged, ResourceInfo, ResourceMoved}

/**
 * Functionality to synchronize a local directory with the database state.
 */
trait LocalResourceSyncer extends LocalDirectoryDependencies {

  private val logger   = scribe.Logger("LocalResourceSyncer")
  private val bucketId = config.id

  private def relativizePath(path: Path): String                    = config.resourcePath.relativize(path).toString
  private def mapFileEvent(fileEvent: FileEvent): IO[ResourceEvent] = {

    def withRequireResource(hash: String, path: Path)(fn: ResourceInfo => ResourceEvent): IO[ResourceEvent] = db.getByHash(bucketId, hash)
      .map(_.find(_.path == relativizePath(path))).flatMap:
        case None    => IO.raiseError(new IllegalStateException("No such file in database"))
        case Some(r) => IO.pure(fn(r))

    fileEvent match {

      case FileMetaChanged(f) => withRequireResource(f.hash, f.path)(r => ResourceFileMetaChanged(r.resourceId, f.modifiedTime))

      case FileDeleted(f) => withRequireResource(f.hash, f.path)(r => ResourceDeleted(r.resourceId))

      case FileAdded(f) => newResource(f, UserId(config.sync.newFilesOwner)).map(ResourceAdded(_))

      case FileMoved(file, oldFilePath) =>
        val newPath = relativizePath(file.path)
        val oldPath = relativizePath(oldFilePath)

        withRequireResource(file.hash, oldFilePath)(r => ResourceMoved(r.resourceId, oldPath, newPath))
    }
  }

  private[local] def newResource(f: FileInfo, userId: UserId): IO[ResourceInfo] =
    LocalResourceMeta(f.path).recover {
      case e => logger.error(s"Failed to resolve meta for ${f.path}", e); None
    }.map { meta =>
      ResourceInfo(
        bucketId           = bucketId,
        resourceId         = config.generateId(),
        userId             = userId,
        path               = relativizePath(f.path),
        hash               = Some(f.hash),
        size               = f.size,
        contentType        = meta.map(_.contentType),
        contentMeta        = meta.map(_.meta),
        timeAdded          = Some(Instant.now().toEpochMilli),
        timeCreated        = None,
        timeLastModified   = Some(f.modifiedTime),
        thumbnailTimestamp = None
      )
    }

  private def toFileStore(): IO[FileStore] =
    db.getAll(bucketId).map { resources =>
      val initialFiles: Seq[FileInfo] = resources
        .map(r => FileInfo(config.resourcePath.resolve(Path.of(r.path)), r.hash.get, r.size, r.timeLastModified.getOrElse(0)))
      InMemoryFileStore(initialFiles)
    }

  /**
   * Given an initial state, this method will poll the directory for changes and emit events for new, deleted and moved resources.
   *
   * The state will be kept in memory and is not persisted.
   */
  private def singleScan(): Stream[IO, ResourceEvent] =
    Stream.eval(toFileStore()).flatMap { fileStore =>
      if Files.exists(config.cachePath) || fileStore.size() == 0 then {
        logger.info(s"Scanning directory: ${config.resourcePath}")
        Files.createDirectories(config.cachePath)
        LocalDirectoryScanner
          .scanDirectory(config.resourcePath, fileStore, config.filterDirectory, config.filterFiles, config.hashingAlgorithm.createHash)
          .parEvalMap(config.sync.scanParallelFactor)(mapFileEvent)
      } else {
        logger.error(
          s"Cache directory ${config.cachePath} does not exist, but database is not empty. This may lead data loss. Not scanning for changes."
        )
        Stream.empty
      }
    }

  /**
   * Given an initial state, this method will poll the directory for changes and emit events for new, deleted and moved resources.
   *
   * The state will be kept in memory and is not persisted.
   */
  private def pollingResourceEventStream(): Stream[IO, ResourceEvent] = Stream.eval(toFileStore()).flatMap: fileStore =>
    LocalDirectoryScanner.pollingStream(
      config.resourcePath,
      fileStore,
      config.sync.pollInterval,
      config.filterDirectory,
      config.filterFiles,
      config.hashingAlgorithm.createHash
    ).parEvalMap(config.sync.scanParallelFactor)(mapFileEvent)

  private def applyEventToDb(event: ResourceEvent): IO[Unit] = event match {
    case ResourceAdded(resource)                       => db.insertResource(resource)
    case ResourceDeleted(resourceId)                   => db.deleteResource(config.id, resourceId)
    case ResourceMoved(id, _, newPath)                 => db.move(config.id, id, newPath)
    case ResourceFileMetaChanged(id, lastModifiedTime) => db.getById(config.id, id).flatMap {
        case Some(resource) => db.upsert(resource.copy(timeLastModified = Some(lastModifiedTime)))
        case None           => IO.unit
      }
    case _                                             => IO.unit
  }

  private[local] def processEvent(event: ResourceEvent) = applyEventToDb(event) >> topic.publish(event) >> IO(logger.info(s"[${config.id}] $event"))

  private def startSync(interrupter: SignallingRef[IO, Boolean]): IO[Unit] = {
    def pollWithRetryOnException(): fs2.Stream[IO, ResourceEvent] =
      pollingResourceEventStream()
        .through(_ evalTap processEvent)
        .interruptWhen(interrupter).handleErrorWith {
          e =>
            logger.error(s"Error while scanning directory ${config.resourcePath.toAbsolutePath}, retrying in ${config.sync.pollInterval}", e)
            fs2.Stream.sleep[IO](config.sync.pollInterval) >> pollWithRetryOnException()
        }

    logger.info(s"Starting polling at interval ${config.sync.pollInterval} for: ${config.resourcePath.toAbsolutePath}")

    pollWithRetryOnException().compile.drain
  }

  def refresh() = singleScan().through(_ evalTap processEvent).compile.drain

  def sync(): IO[Unit] = {
    if config.sync.enabled then
      logger.info(s"Starting sync process using polling for directory: ${config.resourcePath}")
      SignallingRef[IO, Boolean](false).flatMap(signal => startSync(signal))
    else if config.sync.syncOnStartup then {
      logger.info(s"Syncing resources on startup for directory: ${config.resourcePath}")
      refresh()
    } else {
      logger.info(s"Resource sync is disabled for directory: ${config.resourcePath}")
      IO.unit
    }
  }
}

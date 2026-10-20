package nl.amony.modules.resources.local

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import scala.jdk.CollectionConverters.*
import scala.util.Using

import cats.effect.IO
import fs2.Stream
import fs2.concurrent.SignallingRef
import skunk.Session

import nl.amony.lib.files.FileInfo
import nl.amony.lib.files.sync.*
import nl.amony.modules.auth.api.UserId
import nl.amony.modules.resources.api.{
  BucketId,
  ResourceAdded,
  ResourceDeleted,
  ResourceEvent,
  ResourceFileMetaChanged,
  ResourceId,
  ResourceInfo,
  ResourceMoved,
  ResourceMovedAndModified,
  ResourceUpdated,
  VideoProperties
}

/**
 * Functionality to synchronize a local directory with the database state.
 */
trait LocalResourceSyncer extends LocalDirectoryBase {

  self: LocalResourceOperations =>

  private val logger             = scribe.Logger("LocalResourceSyncer")
  private val bucketId: BucketId = BucketId(config.id)

  private def relativizePath(path: Path): String                            = config.resourcePath.relativize(path).toString
  private def mapFileEvent(fileEvent: FileEvent): IO[Option[ResourceEvent]] = {

    def withRequireResource(partialHash: String, path: Path)(fn: ResourceInfo => ResourceEvent): IO[Option[ResourceEvent]] =
      db.getResourceByPartialHash(bucketId, partialHash)
        .map(_.find(_.path == relativizePath(path))).flatMap:
          case None    => IO(logger.warn(s"Dropping file event, no such file in database: $path ($partialHash)")).as(None)
          case Some(r) => IO.pure(Some(fn(r)))

    fileEvent match {

      case FileMetaChanged(f) => withRequireResource(f.partialHash, f.path)(r => ResourceFileMetaChanged(r.resourceId, f.modifiedTime, f.fileKey))

      case FileModified(f) => refreshResource(f, relativizePath(f.path)).map(_.map(ResourceUpdated(_)))

      case FileDeleted(f) => withRequireResource(f.partialHash, f.path)(r => ResourceDeleted(r.resourceId))

      case FileAdded(f) => newResource(f, UserId(config.sync.newFilesOwner)).map(r => Some(ResourceAdded(r)))

      case FileMoved(file, oldFilePath) =>
        val newPath = relativizePath(file.path)
        val oldPath = relativizePath(oldFilePath)

        withRequireResource(file.partialHash, oldFilePath)(r => ResourceMoved(r.resourceId, oldPath, newPath))

      case FileMovedAndModified(file, oldFilePath) =>
        val newPath = relativizePath(file.path)
        val oldPath = relativizePath(oldFilePath)

        refreshResource(file, oldPath).map(_.map(r => ResourceMovedAndModified(r.resourceId, oldPath, newPath, r.copy(path = newPath))))
    }
  }

  private[local] def newResource(f: FileInfo, userId: UserId): IO[ResourceInfo] =
    contentTypeAndMetaForPath(f.path).recover {
      case e => logger.error(s"Failed to resolve meta for ${f.path}", e); None
    }.flatMap { maybeMeta =>
      val contentType = maybeMeta.map(_.contentType)
      streamableOf(f.path, contentType).map { streamable =>
        ResourceInfo(
          bucketId           = bucketId,
          resourceId         = config.generateId(),
          userId             = userId,
          path               = relativizePath(f.path),
          partialHash        = Some(f.partialHash),
          fileKey            = f.fileKey,
          size               = f.size,
          contentType        = contentType,
          contentMeta        = maybeMeta.map(_.meta),
          timeAdded          = Some(Instant.now().toEpochMilli),
          timeLastModified   = Some(f.modifiedTime),
          thumbnailTimestamp = None,
          streamable         = streamable
        )
      }
    }

  /**
   * Refreshes the stored resource for an in-place content change, keeping its identity (and user metadata).
   * Returns None when the database row is gone, in which case the event is dropped.
   */
  private def refreshResource(f: FileInfo, lookupPath: String): IO[Option[ResourceInfo]] =
    db.getResourceByPath(bucketId, lookupPath).flatMap {
      case None           =>
        IO(logger.warn(s"Dropping content-changed event, no such file in database: ${f.path}")).as(None)
      case Some(existing) =>
        contentTypeAndMetaForPath(f.path).recover {
          case e => logger.error(s"Failed to resolve meta for ${f.path}", e); None
        }.flatMap { maybeMeta =>
          val contentType = maybeMeta.map(_.contentType).orElse(existing.contentType)
          streamableOf(f.path, contentType).map { streamable =>
            val durationInMillis   = maybeMeta.flatMap { case (_, meta) =>
              meta.properties match
                case video: VideoProperties => Some(video.durationInMillis)
                case _                      => None
            }
            // a pinned thumbnail timestamp beyond the new duration would yield broken previews
            val thumbnailTimestamp = existing.thumbnailTimestamp.filter(ts => durationInMillis.forall(ts <= _))

            Some(existing.copy(
              partialHash        = Some(f.partialHash),
              fileKey            = f.fileKey.orElse(existing.fileKey),
              size               = f.size,
              contentType        = contentType,
              contentMeta        = maybeMeta.map(_.meta).orElse(existing.contentMeta),
              timeLastModified   = Some(f.modifiedTime),
              thumbnailTimestamp = thumbnailTimestamp,
              streamable         = streamable.orElse(existing.streamable)
            ))
          }
        }
    }

  /** Deletes cached previews derived from a resource, so changed content is never served stale. */
  private def deleteDerivedFiles(resourceId: ResourceId): IO[Unit] = IO.blocking {
    if Files.isDirectory(config.cachePath) then
      Using(Files.list(config.cachePath)) { stream =>
        stream.iterator().asScala
          .filter { path =>
            val name = path.getFileName.toString
            name.startsWith(resourceId.toString + "_") || name.startsWith(resourceId.toString + "-")
          }
          .foreach(path => Files.deleteIfExists(path))
      }
  }.void.handleErrorWith(e => IO(logger.warn(s"Failed to delete derived files for $resourceId", e)))

  private def toFileStore(): IO[FileStore] =
    db.getAll(bucketId).map { resources =>
      val initialFiles: Seq[FileInfo] = resources
        .map(r => FileInfo(config.resourcePath.resolve(Path.of(r.path)), r.partialHash.get, r.size, r.timeLastModified.getOrElse(0), r.fileKey))
      InMemoryFileStore(initialFiles)
    }

  /**
   * Verifies that the bucket identity file matches the expected bucket id.
   * If the database is empty, the identity file is created.
   * Returns true if scanning may proceed, false otherwise.
   */
  private def verifyBucketIdentity(dbIsEmpty: Boolean): Boolean = {
    if dbIsEmpty then {
      Files.createDirectories(config.amonyPath)
      Files.writeString(config.bucketIdPath, bucketId)
      true
    } else if !Files.exists(config.bucketIdPath) then {
      logger.error(
        s"""No Bucket identity file found at ${config.bucketIdPath}
           |This may indicate the wrong directory was mounted. To prevent data loss, scanning will not proceed.
           |To bypass this create a file with the content '$bucketId' at ${config.bucketIdPath}
           |Make sure to verify the following directory is mounted correctly and contains the expected resources before doing so: ${config.resourcePath.toAbsolutePath}
           |""".stripMargin
      )
      false
    } else {
      val storedId = Files.readString(config.bucketIdPath).strip()
      if storedId == bucketId then true
      else {
        logger.error(
          s"""Bucket identity mismatch: expected '$bucketId' but found '$storedId' in ${config.bucketIdPath}.
             |This may indicate the wrong directory was mounted, or that the bucket identity file was modified. To prevent data loss, scanning will not proceed.
             |To bypass this create a file at ${config.bucketIdPath} with the content '$bucketId'.
             |Make sure to verify the following directory is mounted correctly and contains the expected resources before doing so: ${config.resourcePath.toAbsolutePath}
             |""".stripMargin
        )
        false
      }
    }
  }

  private def singleScan(): Stream[IO, ResourceEvent] =
    Stream.eval(toFileStore()).flatMap { fileStore =>
      if verifyBucketIdentity(dbIsEmpty = fileStore.size() == 0) then {
        logger.info(s"Scanning directory: ${config.resourcePath}")
        LocalDirectoryScanner
          .scanDirectory(
            config.resourcePath,
            fileStore,
            config.filterDirectory,
            config.filterFiles,
            config.hashingAlgorithm.createHash,
            parallelFactor
          )
          .parEvalMap(parallelFactor)(mapFileEvent).unNone
      } else {
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
      config.hashingAlgorithm.createHash,
      parallelFactor
    ).parEvalMap(parallelFactor)(mapFileEvent).unNone

  private def applyEventToDb(s: Session[IO], event: ResourceEvent): IO[Unit] = event match {
    case ResourceAdded(resource)                                => db.insertResourceWith(s, resource)
    case ResourceDeleted(resourceId)                            => db.deleteResourceWith(s, bucketId, resourceId)
    case ResourceMoved(id, _, newPath)                          => db.moveWith(s, bucketId, id, newPath)
    case ResourceFileMetaChanged(id, lastModifiedTime, fileKey) => db.getResourceByIdWith(s, bucketId, id).flatMap {
        case Some(resource) =>
          db.upsertResourceWith(s, resource.copy(timeLastModified = Some(lastModifiedTime), fileKey = fileKey.orElse(resource.fileKey)))
        case None           => IO.unit
      }
    case ResourceUpdated(resource)                              => deleteDerivedFiles(resource.resourceId) >> db.upsertResourceWith(s, resource)
    case _                                                      => IO.unit
  }

  private[local] def processEvent(event: ResourceEvent) =
    db.transact(s => applyEventToDb(s, event) >> topic.publish(s, event)) >>
      IO(logger.info(s"[${config.id}] $event"))

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

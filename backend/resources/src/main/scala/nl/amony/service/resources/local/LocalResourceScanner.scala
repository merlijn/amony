package nl.amony.service.resources.local

import cats.effect.IO
import fs2.Stream
import nl.amony.lib.files.watcher.*
import nl.amony.service.resources.Resource
import nl.amony.service.resources.ResourceConfig.LocalDirectoryConfig
import nl.amony.service.resources.api.*
import nl.amony.service.resources.api.events.*
import nl.amony.service.resources.database.ResourceDatabase

import java.nio.file.{Files, Path}

object LocalResourceScanner {
  
  val logger = scribe.Logger("LocalResourceScanner")

  private def mapEvent(db: ResourceDatabase, basePath: Path, bucketId: String)(fileEvent: FileEvent): IO[ResourceEvent] = fileEvent match {
    case FileAdded(f) =>

      LocalResourceMeta.detectMetaData(f.path)
        .recover { case e => logger.error(s"Failed to resolve meta for ${f.path}", e); None }
        .map {
          meta =>
            ResourceAdded(
              ResourceInfo(
                bucketId = bucketId,
                resourceId = f.hash,
                userId = "0",
                path = basePath.relativize(f.path).toString,
                hash = Some(f.hash),
                size = f.size,
                contentType = meta.map(_.contentType),
                contentMetaSource = meta.flatMap(_.toolMeta),
                contentMeta = meta.map(_.meta).getOrElse(ResourceMeta.Empty),
                creationTime = Some(f.creationTime),
                lastModifiedTime = Some(f.modifiedTime),
                thumbnailTimestamp = None
              )
            )
        }

    case FileMetaChanged(file) =>
      IO.pure(ResourceFileMetaChanged(file.hash, Some(file.creationTime), Some(file.modifiedTime)))

    case FileDeleted(f) =>
      IO.pure(ResourceDeleted(f.hash))

    case FileMoved(file, oldPath) =>
      IO.pure(ResourceMoved(file.hash, basePath.relativize(oldPath).toString, basePath.relativize(file.path).toString))
  }

  private def toFileStore(db: ResourceDatabase, config: LocalDirectoryConfig): IO[FileStore] = {
    db.getAll(config.id).map { resources =>
      val initialFiles: Seq[FileInfo] = resources.map { r => FileInfo(config.resourcePath.resolve(Path.of(r.path)), r.hash.get, r.size, r.creationTime.getOrElse(0), r.lastModifiedTime.getOrElse(0)) }
      InMemoryFileStore(initialFiles)
    }
  }

  /**
   * Given an initial state, this method will poll the directory for changes and emit events for new, deleted and moved resources.
   *
   * The state will be kept in memory and is not persisted.
   */
  def singleScan(db: ResourceDatabase, config: LocalDirectoryConfig): Stream[IO, ResourceEvent] = {
    logger.info(s"Scanning directory: ${config.resourcePath}")
    Stream.eval(toFileStore(db, config)).flatMap: fileStore =>
      LocalDirectoryScanner.scanDirectory(config.resourcePath, fileStore, config.filterDirectory, config.filterFiles, config.scan.hashingAlgorithm.createHash)
        .parEvalMap(config.scan.scanParallelFactor)(mapEvent(db, config.resourcePath, config.id))
  }

  /**
   * Given an initial state, this method will poll the directory for changes and emit events for new, deleted and moved resources.
   *
   * The state will be kept in memory and is not persisted.
   */
  def pollingResourceEventStream(db: ResourceDatabase, config: LocalDirectoryConfig): Stream[IO, ResourceEvent] =
    Stream.eval(toFileStore(db, config)).flatMap: fileStore =>
      LocalDirectoryScanner
        .pollingStream(config.resourcePath, fileStore, config.scan.pollInterval, config.filterDirectory, config.filterFiles, config.scan.hashingAlgorithm.createHash)
        .parEvalMap(config.scan.scanParallelFactor)(mapEvent(db, config.resourcePath, config.id))
}

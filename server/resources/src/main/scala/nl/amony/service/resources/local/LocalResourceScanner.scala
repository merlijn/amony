package nl.amony.service.resources.local

import cats.effect.IO
import fs2.Stream
import nl.amony.lib.files.watcher.*
import nl.amony.service.resources.Resource
import nl.amony.service.resources.ResourceConfig.LocalDirectoryConfig
import nl.amony.service.resources.api.*
import nl.amony.service.resources.api.events.*

import java.nio.file.{Files, Path}

object LocalResourceScanner {

  private val logger = org.slf4j.LoggerFactory.getLogger(getClass)

  private def mapEvent(basePath: Path, bucketId: String)(fileEvent: FileEvent): IO[ResourceEvent] = fileEvent match {
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

  private def scan(initialState: Set[ResourceInfo], config: LocalDirectoryConfig, poll: Boolean): Stream[IO, ResourceEvent] = {

    val resourcePath = config.resourcePath

    // prevent data loss in case the cache directory is missing because of an unmounted path
    if (!Files.exists(config.cachePath) && initialState.nonEmpty) {
      logger.error(s"The stored directory state for ${config.resourcePath} is non empty but no cache directory was found. Not continuing to prevent data loss. Perhaps the directory is not mounted? If this is what you intend, please manually create the cache directory at: ${config.cachePath}")
      return Stream.empty
    }

    val initialFiles: Seq[FileInfo] = initialState.map { r => FileInfo(resourcePath.resolve(Path.of(r.path)), r.hash.get, r.size, r.creationTime.getOrElse(0), r.lastModifiedTime.getOrElse(0)) }.toSeq

    val fileStore = InMemoryFileStore(initialFiles)

    def filterFiles(path: Path) = {
      val fileName = path.getFileName.toString
      config.scan.extensions.exists(ext => fileName.endsWith(s".$ext")) && !fileName.startsWith(".")
    }

    def filterDirectory(path: Path) = {
      val fileName = path.getFileName.toString
      !fileName.startsWith(".") && path != config.uploadPath
    }

    if (poll)
      LocalDirectoryScanner
        .pollingStream(resourcePath, fileStore, config.scan.pollInterval, filterDirectory, filterFiles, config.scan.hashingAlgorithm.createHash)
        .parEvalMap(config.scan.scanParallelFactor)(mapEvent(resourcePath, config.id))
    else
      LocalDirectoryScanner.scanDirectory(resourcePath, fileStore, filterDirectory, filterFiles, config.scan.hashingAlgorithm.createHash)
        .parEvalMap(config.scan.scanParallelFactor)(mapEvent(resourcePath, config.id))
  }

  /**
   * Given an initial state, this method will poll the directory for changes and emit events for new, deleted and moved resources.
   *
   * The state will be kept in memory and is not persisted.
   */
  def singleScan(initialState: Set[ResourceInfo], config: LocalDirectoryConfig): Stream[IO, ResourceEvent] = {
    logger.info(s"Scanning directory: ${config.resourcePath}")
    scan(initialState, config, poll = false)
  }

  /**
   * Given an initial state, this method will poll the directory for changes and emit events for new, deleted and moved resources.
   *
   * The state will be kept in memory and is not persisted.
   */
  def pollingResourceEventStream(initialState: Set[ResourceInfo], config: LocalDirectoryConfig): Stream[IO, ResourceEvent] =
    scan(initialState, config, poll = true)
}

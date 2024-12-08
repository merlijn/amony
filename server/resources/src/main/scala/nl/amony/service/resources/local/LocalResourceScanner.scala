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
  
  val logger = scribe.Logger("LocalResourceScanner")

  /**
   * Given an initial state, this method will poll the directory for changes and emit events for new, deleted and moved resources.
   *
   * The state will be kept in memory and is not persisted.
   */
  def pollingResourceEventStream(initialState: Set[ResourceInfo], config: LocalDirectoryConfig): Stream[IO, ResourceEvent] = {

    val resourcePath = config.resourcePath

    // prevent data loss in case the cache directory is missing because of an unmounted path
    if (!Files.exists(config.cachePath) && initialState.nonEmpty) {
      logger.error(s"The stored directory state for ${config.resourcePath} is non empty but no cache directory was found. Not continuing to prevent data loss. Perhaps the directory is not mounted? If this is what you intend, please manually create the cache directory at: ${config.cachePath}")
      return Stream.empty
    }
    
    val initialFiles: Map[Path, FileInfo] = initialState.map { r =>
      val path = resourcePath.resolve(Path.of(r.path))
      path -> FileInfo(resourcePath.resolve(Path.of(r.path)), r.hash, r.size, r.creationTime.getOrElse(0), r.lastModifiedTime.getOrElse(0))
    }.toMap

    def filterPath(path: Path) = {

      val fileName = path.getFileName.toString
      config.scan.extensions.exists(ext => fileName.endsWith(s".$ext")) && !fileName.startsWith(".")
    }
    
    def filterDirectory(path: Path) = {
      val fileName = path.getFileName.toString
      !fileName.startsWith(".") && path != config.uploadPath
    }

    LocalDirectoryScanner.pollingStream(resourcePath, initialFiles, config.scan.pollInterval, filterDirectory, filterPath, config.scan.hashingAlgorithm.createHash).parEvalMap(config.scan.scanParallelFactor) {
      case FileAdded(f) =>

          LocalResourceMeta.resolveMeta(f.path)
            .map(_.getOrElse(ResourceMeta.Empty))
            .recover {
              case e => logger.error(s"Failed to resolve meta for ${f.path}", e); ResourceMeta.Empty
            }.map { meta =>
              ResourceAdded(
                ResourceInfo(
                  bucketId = config.id,
                  path = resourcePath.relativize(f.path).toString,
                  hash = f.hash,
                  size = f.size,
                  contentType = Resource.contentTypeForPath(f.path),
                  contentMeta = meta,
                  creationTime = Some(f.creationTime),
                  lastModifiedTime = Some(f.modifiedTime),
                  thumbnailTimestamp = None
                )
              )
            }

      case FileDeleted(f) =>
        IO.pure(ResourceDeleted(f.hash))

      case FileMoved(f, oldPath) =>
        IO.pure(ResourceMoved(f.hash, resourcePath.relativize(oldPath).toString, resourcePath.relativize(f.path).toString))
    }
  }
}

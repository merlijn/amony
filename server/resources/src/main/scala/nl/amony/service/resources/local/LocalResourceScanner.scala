package nl.amony.service.resources.local

import cats.effect.IO
import fs2.Stream
import nl.amony.lib.files.watcher.{FileAdded, FileDeleted, FileInfo, FileMoved, LocalDirectoryScanner}

import java.nio.file.{Files, Path}
import scala.concurrent.duration.FiniteDuration
import nl.amony.service.resources.Resource
import nl.amony.service.resources.api.*
import nl.amony.service.resources.api.events.*
import nl.amony.service.resources.ResourceConfig.LocalDirectoryConfig

import java.io.{File, FilenameFilter}
import java.nio.file.attribute.BasicFileAttributes

object LocalResourceScanner {
  
  val logger = scribe.Logger("LocalResourceScanner")

  /**
   * Given an initial state, this method will poll the directory for changes and emit events for new, deleted and moved resources.
   *
   * The state will be kept in memory and is not persisted.
   */
  def pollingResourceEventStream(initialState: Set[ResourceInfo], config: LocalDirectoryConfig): Stream[IO, ResourceEvent] = {

    val mediaPath = config.resourcePath
    
    val initialFiles: Map[Path, FileInfo] = initialState.map { r =>
      val path = mediaPath.resolve(Path.of(r.path))
      path -> FileInfo(mediaPath.resolve(Path.of(r.path)), r.hash, r.size, r.creationTime.getOrElse(0), r.lastModifiedTime.getOrElse(0))
    }.toMap

    def filterPath(path: Path) = config.filterFileName(path.getFileName.toString)

    LocalDirectoryScanner.pollingStream(mediaPath, initialFiles, config.pollInterval, filterPath, config.hashingAlgorithm.createHash).parEvalMap(4) {
      case FileAdded(f) =>

        val resourceMeta: IO[ResourceMeta] =
          LocalResourceMeta.resolveMeta(f.path)
            .map(_.getOrElse(ResourceMeta.Empty))
            .recover {
              case e => logger.error(s"Failed to resolve meta for ${f.path}", e); ResourceMeta.Empty
            }

        for {
          meta <- resourceMeta
          thumbnailTimestamp = meta match {
            case vid: VideoMeta =>
                val ts = config.cachePath.toFile.list((dir: File, name: String) => name.startsWith(f.hash) && name.endsWith(".webp")).toList.map {
                  case name @ s"${hash}_${timestamp}_${quality}.webp" =>
                    val path = config.cachePath.resolve(name)
                    val attrs = Files.readAttributes(path, classOf[BasicFileAttributes])
                    timestamp.toLong -> attrs.creationTime().toMillis
                }.maxByOption(_._2).map(_._1)
                ts.foreach(t => logger.info(s"Recovered timestamp $t for ${f.path}"))
                ts
            case _ => None
          }
        } yield ResourceAdded(ResourceInfo(
          bucketId = config.id,
          path = mediaPath.relativize(f.path).toString,
          hash = f.hash,
          size = f.size,
          contentType = Resource.contentTypeForPath(f.path),
          contentMeta = meta,
          creationTime = Some(f.creationTime),
          lastModifiedTime = Some(f.modifiedTime),
          thumbnailTimestamp = thumbnailTimestamp
        ))

      case FileDeleted(f) =>
        IO(ResourceDeleted(f.hash))

      case FileMoved(f, oldPath) =>
        IO(ResourceMoved(f.hash, mediaPath.relativize(oldPath).toString, mediaPath.relativize(f.path).toString))
    }
  }
}

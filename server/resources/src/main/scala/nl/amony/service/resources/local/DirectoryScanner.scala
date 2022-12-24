package nl.amony.service.resources.local

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import fs2.Stream
import nl.amony.lib.files.FileUtil
import nl.amony.service.resources.ResourceConfig.LocalResourcesConfig
import scribe.Logging

import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

object DirectoryScanner extends Logging {

  sealed trait LocalResourceEvent

  case class FileAdded(file: LocalFile) extends LocalResourceEvent
  case class FileDeleted(hash: String, relativePath: String) extends LocalResourceEvent
  case class FileMoved(hash: String, oldPath: String, newPath: String) extends LocalResourceEvent

  case class LocalFile(
                        relativePath: String,
                        hash: String,
                        size: Long,
                        creationTime: Long,
                        lastModifiedTime: Long) {
    def extension: String = relativePath.split('.').last

    def hasEqualMeta(other: LocalFile) = {
      // this depends on file system meta data and the fact that a file move does not update these attributes
      hash == other.hash && creationTime == other.creationTime && lastModifiedTime == other.lastModifiedTime
    }
  }

  def scanDirectory(config: LocalResourcesConfig, snapshot: Set[LocalFile]): Stream[IO, LocalFile] = {

    val mediaPath = config.mediaPath
    val hashingAlgorithm = config.hashingAlgorithm

    Stream.fromIterator[IO](FileUtil.listFilesInDirectoryRecursive(mediaPath).iterator, 10)
      .filter { file => config.filterFileName(file.getFileName.toString) }
      .filter { file =>
        val isEmpty = Files.size(file) == 0
        if (isEmpty)
          logger.warn(s"Encountered empty file: ${file.getFileName.toString}")
        !isEmpty
      }
      .parEvalMapUnordered(config.scanParallelFactor) { path =>
        IO {

          val relativePath = mediaPath.relativize(path).toString
          val fileAttributes = Files.readAttributes(path, classOf[BasicFileAttributes])

          val hash = if (config.verifyExistingHashes) {
            hashingAlgorithm.createHash(path)
          } else {
            snapshot.find(_.relativePath == relativePath) match {
              case None => hashingAlgorithm.createHash(path)
              case Some(m) =>
                if (m.lastModifiedTime != fileAttributes.lastModifiedTime().toMillis) {
                  logger.warn(s"$path last modified time is different from what last seen, recomputing hash")
                  hashingAlgorithm.createHash(path)
                } else {
                  m.hash
                }
            }
          }

          LocalFile(relativePath, hash, fileAttributes.size(), fileAttributes.creationTime().toMillis, fileAttributes.lastModifiedTime().toMillis)
        }
      }
  }

  def diff(config: LocalResourcesConfig, snapshot: Set[LocalFile])(implicit ioRuntime: IORuntime): List[LocalResourceEvent] = {

    val scannedResources: Set[LocalFile] = scanDirectory(config, snapshot).compile.toList.unsafeRunSync().toSet

    val (colliding, nonColliding) = scannedResources
      .groupBy(_.hash)
      .partition { case (_, files) => files.size > 1 }

    colliding.foreach { case (hash, files) =>
      val collidingFiles = files.map(_.relativePath).mkString("\n")
      logger.warn(s"The following files share the same hash and will be ignored ($hash):\n$collidingFiles")
    }

    val nonCollidingResources = nonColliding.map(_._2).flatten

    val newResources: List[FileAdded] =
      nonCollidingResources
        .filterNot(r => snapshot.exists(_.hash == r.hash))
        .map(r => FileAdded(r))
        .toList

    val deletedResources: List[FileDeleted] =
      snapshot
        .filterNot(r => scannedResources.exists(_.hash == r.hash))
        .map(r => FileDeleted(r.hash, r.relativePath))
        .toList

    val movedResources: List[FileMoved] =
      snapshot.flatMap { old =>

        def equalMeta(): Option[LocalFile] = scannedResources.find { n => old.relativePath != n.relativePath && old.hasEqualMeta(n) }

        def equalHash(): Option[LocalFile] = scannedResources.find { n => old.relativePath != n.relativePath && old.hash == n.hash }

        // prefer the file with equal timestamp meta, otherwise fall back to just equal hash
        equalMeta().orElse(equalHash()).map { n => FileMoved(n.hash, old.relativePath, n.relativePath) }

      }.toList

    newResources.foreach(e => logger.info(s"new file: ${e.file.relativePath}"))
    deletedResources.foreach(e => logger.info(s"deleted file: ${e.relativePath}"))
    movedResources.foreach(e => logger.info(s"moved file: ${e.oldPath} -> ${e.newPath}"))

    newResources ::: deletedResources ::: movedResources
  }
}

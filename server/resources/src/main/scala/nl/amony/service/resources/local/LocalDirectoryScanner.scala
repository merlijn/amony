package nl.amony.service.resources.local

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import fs2.Stream
import nl.amony.lib.eventbus.EventTopic
import nl.amony.service.resources.ResourceContent
import nl.amony.service.resources.ResourceConfig.LocalDirectoryConfig
import nl.amony.service.resources.api.Resource
import nl.amony.service.resources.api.events._
import nl.amony.service.resources.local.db.LocalDirectoryDb
import scribe.Logging
import slick.jdbc.JdbcProfile

import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

class LocalDirectoryScanner[P <: JdbcProfile](config: LocalDirectoryConfig, storage: LocalDirectoryDb[P])(implicit runtime: IORuntime) extends Logging {

  private def scanDirectory(cache: String => Option[Resource]): Stream[IO, Resource] = {

    val mediaPath = config.resourcePath
    val hashingAlgorithm = config.hashingAlgorithm

    Stream.fromIterator[IO](RecursiveFileVisitor.listFilesInDirectoryRecursive(mediaPath).iterator, 10)
      .filter { file => config.filterFileName(file.getFileName.toString) }
      .filter { file =>
        val isEmpty = Files.size(file) == 0
        if (isEmpty)
          logger.warn(s"Ignoring empty file: ${file.getFileName.toString}")
        !isEmpty
      }
      .parEvalMapUnordered(config.scanParallelFactor) { path =>

        IO {
          val relativePath = mediaPath.relativize(path).toString
          val fileAttributes = Files.readAttributes(path, classOf[BasicFileAttributes])

          val hash = if (config.verifyExistingHashes) {
            hashingAlgorithm.createHash(path)
          } else {
            cache(relativePath) match {
              case None => hashingAlgorithm.createHash(path)
              case Some(m) =>
                if (m.lastModifiedTime != Some(fileAttributes.lastModifiedTime().toMillis)) {
                  logger.warn(s"$path last modified time is different from what last seen, recomputing hash")
                  hashingAlgorithm.createHash(path)
                } else {
                  m.hash
                }
            }
          }

          Resource(
            bucketId = config.id,
            parentId = None,
            path = relativePath,
            hash = hash,
            fileAttributes.size(),
            contentType = ResourceContent.contentTypeForPath(path),
            Some(fileAttributes.creationTime().toMillis),
            Some(fileAttributes.lastModifiedTime().toMillis))
        }
      }
  }

  // this depends on file system meta data and the fact that a file move does not update these attributes
  def hasEqualMeta(a: Resource, b: Resource) = {
    a.hash == b.hash && a.creationTime == b.creationTime && a.lastModifiedTime == b.lastModifiedTime
  }

  def diff(previousState: Seq[Resource]): List[ResourceEvent] = {

    val scannedResources: Set[Resource] = scanDirectory(path => previousState.find(_.path == path)).compile.toList.unsafeRunSync().toSet

    val (colliding, nonColliding) = scannedResources
      .groupBy(_.hash)
      .partition { case (_, files) => files.size > 1 }

    colliding.foreach { case (hash, files) =>
      val collidingFiles = files.map(_.path).mkString("\n")
      logger.warn(s"The following files share the same hash and will be ignored ($hash):\n$collidingFiles")
    }

    val nonCollidingResources = nonColliding.map(_._2).flatten

    val newResources: List[ResourceAdded] =
      nonCollidingResources
        .filterNot(r => previousState.exists(_.hash == r.hash))
        .map(r => ResourceAdded(r))
        .toList

    val deletedResources: List[ResourceDeleted] =
      previousState
        .filterNot(r => scannedResources.exists(_.hash == r.hash))
        .map(r => ResourceDeleted(r))
        .toList

    val movedResources: List[ResourceMoved] =
      previousState.flatMap { old =>

        // TODO there are some edge cases where this does not work

        def equalMeta(): Option[Resource] = scannedResources.find { current => old.path != current.path && hasEqualMeta(current, old) }
        def equalHash(): Option[Resource] = scannedResources.find { current => old.path != current.path && old.hash == current.hash }

        // prefer the file with equal timestamp meta, otherwise fall back to just equal hash
        equalMeta().orElse(equalHash()).map { moved => ResourceMoved(old.copy(path = moved.path), old.path) }

      }.toList

    newResources.foreach(e => logger.info(s"new file: ${e.resource.path}"))
    deletedResources.foreach(e => logger.info(s"deleted file: ${e.resource.path}"))
    movedResources.foreach(e => logger.info(s"moved file: ${e.oldPath} -> ${e.resource.path}"))

    newResources ::: deletedResources ::: movedResources
  }

  def sync(topic: EventTopic[ResourceEvent]): Unit =
    diff(storage.getAll(config.id).unsafeRunSync()).foreach {
      case e @ ResourceAdded(resource) =>
        logger.info(s"File added: ${resource.path}")
        storage.insert(resource, IO.unit).unsafeRunSync()
        topic.publish(e)
      case e @ ResourceDeleted(resource) =>
        logger.info(s"File deleted: ${resource.path}")
        storage.deleteByRelativePath(resource.bucketId, resource.path).unsafeRunSync()

        topic.publish(e)
      case e @ ResourceMoved(resource, oldPath) =>
        logger.info(s"File moved: ${oldPath} -> ${resource.path}")
        storage.move(resource.bucketId, oldPath, resource).unsafeRunSync()
        topic.publish(e)
    }
}

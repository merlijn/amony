package nl.amony.service.resources.local

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import cats.implicits.*
import fs2.Stream
import fs2.Stream.suspend
import nl.amony.service.resources.ResourceConfig.LocalDirectoryConfig
import nl.amony.service.resources.ResourceContent
import nl.amony.service.resources.api.events.*
import nl.amony.service.resources.api.operations.ResourceOperation
import nl.amony.service.resources.api.{ResourceInfo, ResourceMeta}
import nl.amony.service.resources.local.db.LocalDirectoryDb
import scribe.Logging
import slick.jdbc.JdbcProfile

import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{Files, Path}
import scala.concurrent.duration.FiniteDuration

class LocalDirectoryScanner[P <: JdbcProfile](config: LocalDirectoryConfig, storage: LocalDirectoryDb[P])(implicit runtime: IORuntime) extends Logging {

  private def scanDirectory(previousState: Set[ResourceInfo]): Stream[IO, ResourceInfo] = {

    val mediaPath = config.resourcePath
    val hashingAlgorithm = config.hashingAlgorithm

    def getByPath(path: String): Option[ResourceInfo] = previousState.find(_.path == path)
    def getByHash(hash: String): Option[ResourceInfo] = previousState.find(_.hash == hash)
    def filterPath(path: Path) = config.filterFileName(path.getFileName.toString)

    logger.info(s"Scanning directory: ${mediaPath.toAbsolutePath}")

    def allFiles() = RecursiveFileVisitor.listFilesInDirectoryRecursive(mediaPath, filterPath)

    Stream.fromIterator[IO](allFiles().iterator, 10)
      .filter { file =>
        val isEmpty = Files.size(file) == 0
        if (isEmpty)
          logger.warn(s"Ignoring empty file: ${file.getFileName.toString}")
        !isEmpty
      }
      .parEvalMapUnordered(config.scanParallelFactor) { path =>

        val relativePath = mediaPath.relativize(path).toString
        val fileAttributes = Files.readAttributes(path, classOf[BasicFileAttributes])

        for  {
          hash <- IO {
            if (config.verifyExistingHashes) {
              hashingAlgorithm.createHash(path)
            } else {
              getByPath(relativePath) match {
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
          }
          meta <-
            getByHash(hash) match {
              case None    =>
                logger.info(s"Scanning new file $relativePath")
                val path = mediaPath.resolve(relativePath)
                LocalResourceMeta.resolveMeta(path).map(_.getOrElse(ResourceMeta.Empty))
              case Some(m) => IO.pure(m.contentMeta)
            }

        } yield {
          ResourceInfo(
            bucketId = config.id,
            parentId = None,
            path = relativePath,
            hash = hash,
            fileAttributes.size(),
            contentType = ResourceContent.contentTypeForPath(path), // apache tika?
            contentMeta = meta,
            operation = ResourceOperation.Empty,
            Some(fileAttributes.creationTime().toMillis),
            Some(fileAttributes.lastModifiedTime().toMillis))
        }
      }
  }
  
  def diff(previousState: Set[ResourceInfo], currentState: Set[ResourceInfo]): List[ResourceEvent] = {

    val (colliding, nonColliding) = currentState
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
        .filterNot(r => currentState.exists(_.hash == r.hash))
        .map(r => ResourceDeleted(r))
        .toList

    val movedResources: List[ResourceMoved] =
      previousState.flatMap { old =>

        // this depends on file system meta data and the fact that a file move does not update these attributes
        def hasEqualMeta(a: ResourceInfo, b: ResourceInfo) =
          a.hash == b.hash && a.creationTime == b.creationTime && a.lastModifiedTime == b.lastModifiedTime

        def findByMeta(): Option[ResourceInfo] = currentState.find { current => old.path != current.path && hasEqualMeta(current, old) }
        def findByHash(): Option[ResourceInfo] = currentState.find { current => old.path != current.path && old.hash == current.hash }

        // prefer the file with equal timestamp meta, otherwise fall back to just equal hash
        findByMeta().orElse(findByHash()).map { moved => ResourceMoved(old.copy(path = moved.path), old.path) }

      }.toList

    newResources ::: deletedResources ::: movedResources
  }
  
  def pollingStream(initialState: Set[ResourceInfo], pollInterval: FiniteDuration): Stream[IO, ResourceEvent] = {

    def next(prev: Set[ResourceInfo]): IO[(Seq[ResourceEvent], Set[ResourceInfo])] =
      scanDirectory(prev).compile.toList.map { current => diff(prev, current.toSet) -> current.toSet }

    def recur(prev: Set[ResourceInfo]): Stream[IO, Seq[ResourceEvent]] =
      Stream.eval(next(prev)).flatMap:
        case (o, s) => Stream.emit(o) ++ (Stream.sleep[IO](pollInterval) >> recur(s))

    Stream.suspend(recur(initialState)).flatMap(Stream.emits)
  }
}

package nl.amony.service.resources.local

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import nl.amony.lib.eventbus.EventTopic
import nl.amony.lib.files.PathOps
import nl.amony.service.resources.ResourceConfig.LocalDirectoryConfig
import nl.amony.service.resources.*
import nl.amony.service.resources.api.events.{ResourceEvent, ResourceUserMetaUpdated}
import nl.amony.service.resources.api.{ResourceInfo, ResourceMeta}
import nl.amony.service.resources.api.operations.{ImageThumbnail, ResourceOperation, VideoFragment, VideoThumbnail}
import nl.amony.service.resources.local.LocalResourceOperations.*
import nl.amony.service.resources.local.db.LocalDirectoryDb
import scribe.Logging
import slick.jdbc.JdbcProfile

import java.nio.file.{Files, Path}
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.ExecutionContext

class LocalDirectoryBucket[P <: JdbcProfile](config: LocalDirectoryConfig, db: LocalDirectoryDb[P], topic: EventTopic[ResourceEvent]) extends ResourceBucket with Logging {

  private val resourceStore = new ConcurrentHashMap[ResourceOp, IO[Path]]()

  Files.createDirectories(config.writePath)

  private def getFileInfo(resourceId: String): IO[Option[ResourceInfo]] =
    db.getByHash(config.id, resourceId)

  override def getOrCreate(resourceId: String, operation: ResourceOperation, tags: Set[String]): IO[Option[ResourceContent]] = {

    getFileInfo(resourceId).flatMap {
      case None => IO.pure(None)
      case Some(fileInfo) =>
        val localFileOp = operation match {
          case VideoFragment(width, height, start, end, quality) => VideoFragmentOp(resourceId, (start, end), height.get)
          case VideoThumbnail(width, height, quality, timestamp) => VideoThumbnailOp(resourceId, timestamp, height.get)
          case ImageThumbnail(width, height, quality)            => ImageThumbnailOp(resourceId, width, height)
          case _                                                 => NoOp
        }

        derivedResource(fileInfo, localFileOp)
    }
  }

  private def derivedResource(fileInfo: ResourceInfo, operation: ResourceOp): IO[Option[LocalFileContent]] = {

    // this is to prevent 2 or more requests for the same resource to trigger the operation multiple times
    val result = resourceStore.compute(operation, (_, value) => {
      val outputDir = config.writePath
      val file = outputDir.resolve(operation.outputFilename)
      if (!file.exists())
        operation.createFile(config.resourcePath.resolve(fileInfo.path), outputDir).memoize.flatten
      else
        IO.pure(file)
    })

    result.map(path => ResourceContent.fromPath(path, fileInfo))
  }

  override def getResource(resourceId: String): IO[Option[ResourceContent]] = {
    getFileInfo(resourceId).flatMap {
      case None       => IO.pure(None)
      case Some(info) =>
        val path = config.resourcePath.resolve(info.path)
        IO.pure(ResourceContent.fromPath(path, info))
    }
  }

  override def getChildren(resourceId: String, tags: Set[String]): IO[Seq[ResourceInfo]] =
    db.getChildren(config.id, resourceId, tags)

  override def uploadResource(fileName: String, source: fs2.Stream[IO, Byte]): IO[ResourceInfo] = ???

  override def deleteResource(resourceId: String): IO[Unit] =
    getFileInfo(resourceId).flatMap {
      case None       => IO.pure(())
      case Some(info) =>
        db.deleteResource(config.id, resourceId)
        val path = config.resourcePath.resolve(info.path)

        IO(path.deleteIfExists())
    }

  override def updateUserMeta(resourceId: String, title: Option[String], description: Option[String], tags: List[String]): IO[Unit] =
    db.updateUserMeta(
      config.id, resourceId, title, description, tags, 
      IO { topic.publish(ResourceUserMetaUpdated(config.id, resourceId, title, description, tags)) }
    )
}

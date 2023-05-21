package nl.amony.service.resources.local

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import nl.amony.lib.ffmpeg.FFMpeg
import nl.amony.lib.files.PathOps
import nl.amony.lib.magick.ImageMagick
import nl.amony.service.resources.ResourceConfig.LocalDirectoryConfig
import nl.amony.service.resources._
import nl.amony.service.resources.api.Resource
import nl.amony.service.resources.local.LocalResourceOperations._
import scribe.Logging
import slick.jdbc.JdbcProfile

import java.nio.file.{Files, Path}
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.ExecutionContext

class LocalDirectoryBucket[P <: JdbcProfile](config: LocalDirectoryConfig, repository: LocalDirectoryStorage[P])(implicit ec: ExecutionContext) extends ResourceBucket with Logging {

  private val resourceStore = new ConcurrentHashMap[ResourceOp, IO[Path]]()

  // TODO think about replacing this with custom runtime
  implicit val runtime: IORuntime = IORuntime.global

  Files.createDirectories(config.writePath)

  private def getFileInfo(resourceId: String): IO[Option[Resource]] =
    repository.getByHash(resourceId)

  override def getOrCreate(resourceId: String, operation: ResourceOperation, tags: Set[String]): IO[Option[ResourceContent]] = {

    getFileInfo(resourceId).flatMap {
      case None => IO.pure(None)
      case Some(fileInfo) =>
        val localFileOp = operation match {
          case VideoFragment(start, end, quality) => VideoFragmentOp(resourceId, (start, end), quality)
          case VideoThumbnail(timestamp, quality) => VideoThumbnailOp(resourceId, timestamp, quality)
          case ImageThumbnail(scaleHeight)        => ImageThumbnailOp(resourceId, scaleHeight)
        }

        derivedResource(fileInfo, localFileOp)
    }
  }

  private def derivedResource(fileInfo: Resource, key: ResourceOp): IO[Option[LocalFileContent]] = {
    val path = config.writePath.resolve(key.outputFilename)
    // this is to prevent 2 or more requests for the same resource to trigger the operation multiple times
    val result = resourceStore.compute(key, (_, value) =>
      if (!path.exists())
        key.create(config, fileInfo.path).memoize.flatten
      else
        IO.pure(path)
    )
    result.map(ResourceContent.fromPath)
  }

  override def getContent(resourceId: String): IO[Option[ResourceContent]] = {
    getFileInfo(resourceId).flatMap {
      case None       => IO.pure(None)
      case Some(info) =>
        val path = config.resourcePath.resolve(info.path)
        IO.pure(ResourceContent.fromPath(path))
    }
  }

  override def getResourceMeta(resourceId: String): IO[Option[ResourceMeta]] = {

    getFileInfo(resourceId).flatMap {
      case None       => IO.pure(None)
      case Some(info) =>
        val path = config.resourcePath.resolve(info.path)

        info.contentType match {
          case None =>
            logger.info(s"No content type for $resourceId")
            IO.pure(None)
          case Some(contentType) if contentType.startsWith("video") =>
            FFMpeg.ffprobe(path, false).map {
              probe => probe.firstVideoStream.map { stream =>
                VideoMeta(
                  contentType = contentType,
                  width  = stream.width,
                  height = stream.height,
                  durationInMillis = stream.durationMillis,
                  fps = stream.fps.toFloat,
                  metaData = Map.empty,
                )
              }
            }
          case Some(contentType) if contentType.startsWith("image") =>
            ImageMagick.getImageMeta(path).map(out =>
              out.headOption.map { meta =>
                ImageMeta(
                  contentType = contentType,
                  width = meta.image.geometry.width,
                  height = meta.image.geometry.height,
                  metaData = meta.image.properties,
                )
              }
            )
          case Some(contentType) => IO.pure(Some(Other(contentType)))
        }
    }
  }

  override def uploadResource(fileName: String, source: fs2.Stream[IO, Byte]): IO[Boolean] = ???
}

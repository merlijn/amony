package nl.amony.service.resources.local

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import nl.amony.lib.ffmpeg.FFMpeg
import nl.amony.lib.files.PathOps
import nl.amony.lib.magick.ImageMagick
import nl.amony.service.resources.ResourceConfig.LocalDirectoryConfig
import nl.amony.service.resources._
import nl.amony.service.resources.events.Resource
import scribe.Logging
import slick.jdbc.JdbcProfile

import java.nio.file.{Files, Path}
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.ExecutionContext

sealed trait ResourceKey

case class VideoThumbnailKey(resourceId: String, timestamp: Long, quality: Int) extends ResourceKey {
  def path: String = s"${resourceId}_${timestamp}_${quality}p.webp"
}

case class ImageThumbnailKey(resourceId: String, quality: Int) extends ResourceKey {
  def path: String = s"${resourceId}_${quality}p.webp"
}

case class FragmentKey(resourceId: String, range: (Long, Long), quality: Int) extends ResourceKey {
  def path: String = s"${resourceId}_${range._1}-${range._2}_${quality}p.mp4"
}

class LocalDirectoryBucket[P <: JdbcProfile](config: LocalDirectoryConfig, repository: LocalDirectoryStorage[P])(implicit ec: ExecutionContext) extends ResourceBucket with Logging {

  private val resourceStore = new ConcurrentHashMap[ResourceKey, IO[Path]]()

  // TODO think about replacing this with custom runtime
  implicit val runtime: IORuntime = IORuntime.global

  Files.createDirectories(config.resourcePath)

  private def getFileInfo(resourceId: String): IO[Option[Resource]] =
    repository.getByHash(resourceId)

  override def getOrCreate(resourceId: String, operation: ResourceOperation): IO[Option[ResourceContent]] = {

    getFileInfo(resourceId).flatMap { maybeResource =>
      maybeResource match {
        case None => IO.pure(None)
        case Some(fileInfo) =>
          operation match {
            case VideoFragment(start, end, quality) =>
              val key = FragmentKey(resourceId, (start, end), quality)
              val path = resourceStore.compute(key, (_, value) => getOrCreateVideoFragment(fileInfo, key))

              path.map(ResourceContent.fromPath)

            case VideoThumbnail(timestamp, quality) =>
              val key = VideoThumbnailKey(resourceId, timestamp, quality)
              val path = resourceStore.compute(key, (_, value) => getOrCreateThumbnail(fileInfo, key))

              path.map(ResourceContent.fromPath)

            case ImageThumbnail(scaleHeight) =>
              val key = ImageThumbnailKey(resourceId, scaleHeight)
              val path = resourceStore.compute(key, (_, value) => getOrCreateImageThumbnail(fileInfo, key))

              path.map(ResourceContent.fromPath)
        }
      }
    }
  }

  private def getOrCreateVideoFragment(resourceInfo: Resource, key: FragmentKey): IO[Path] = {

    val fragmentPath = config.resourcePath.resolve(key.path)

    if (!fragmentPath.exists()) {
      logger.debug(s"Creating fragment for ${resourceInfo.path} with range ${key.range}")
      FFMpeg.transcodeToMp4(
        inputFile = config.mediaPath.resolve(resourceInfo.path),
        range = key.range,
        crf = 23,
        scaleHeight = Some(key.quality),
        outputFile = Some(fragmentPath),
      ).map(_ => fragmentPath).memoize.flatten
    }
    else
      IO.pure(fragmentPath)
  }

  private def getOrCreateThumbnail(resourceInfo: Resource, key: VideoThumbnailKey): IO[Path] = {
    val thumbnailPath = config.resourcePath.resolve(key.path)

    if (!thumbnailPath.exists()) {

      logger.debug(s"Creating thumbnail for ${resourceInfo.path} with timestamp ${key.timestamp}")

      FFMpeg.createThumbnail(
        inputFile   = config.mediaPath.resolve(resourceInfo.path),
        timestamp   = key.timestamp,
        outputFile  = Some(thumbnailPath),
        scaleHeight = Some(key.quality)
      ).map(_ => thumbnailPath).memoize.flatten
    }
    else
      IO.pure(thumbnailPath)
  }

  private def getOrCreateImageThumbnail(resourceInfo: Resource, key: ImageThumbnailKey): IO[Path] = {
    val thumbnailPath = config.resourcePath.resolve(key.path)

    if (!thumbnailPath.exists()) {

      logger.debug(s"Creating image thumbnail for ${resourceInfo.path}")

      ImageMagick.createThumbnail(
        inputFile   = config.mediaPath.resolve(resourceInfo.path),
        outputFile  = Some(thumbnailPath),
        scaleHeight = key.quality
      ).map(_ => thumbnailPath).memoize.flatten
    }
    else {
      IO.pure(thumbnailPath)
    }
  }

  override def getContent(resourceId: String): IO[Option[ResourceContent]] = {
    getFileInfo(resourceId).flatMap {
      case None       => IO.pure(None)
      case Some(info) =>
        val path = config.mediaPath.resolve(info.path)
        IO.pure(ResourceContent.fromPath(path))
    }
  }

  override def getResourceMeta(resourceId: String): IO[Option[ResourceMeta]] = {

    getFileInfo(resourceId).flatMap {
      case None       => IO.pure(None)
      case Some(info) =>
        val path = config.mediaPath.resolve(info.path)

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

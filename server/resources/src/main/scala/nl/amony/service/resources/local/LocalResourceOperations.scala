package nl.amony.service.resources.local

import cats.effect.IO
import nl.amony.lib.ffmpeg.FFMpeg
import nl.amony.lib.magick.ImageMagick
import nl.amony.service.resources.ResourceConfig.LocalDirectoryConfig
import scribe.Logging

import java.nio.file.Path

object LocalResourceOperations {

  sealed trait ResourceKey {

    def outputPath: String
    def create(config: LocalDirectoryConfig, relativePath: String): IO[Path]
  }

  case class VideoThumbnailKey(resourceId: String, timestamp: Long, quality: Int) extends ResourceKey with Logging {
    def outputPath: String = s"${resourceId}_${timestamp}_${quality}p.webp"

    override def create(config: LocalDirectoryConfig, relativePath: String): IO[Path] = {

      val outputFile = config.resourcePath.resolve(outputPath)
      logger.debug(s"Creating thumbnail for ${relativePath} with timestamp ${timestamp}")

      FFMpeg.createThumbnail(
        inputFile = config.mediaPath.resolve(relativePath),
        timestamp = timestamp,
        outputFile = Some(outputFile),
        scaleHeight = Some(quality)
      ).map(_ => outputFile)
    }
  }

  case class ImageThumbnailKey(resourceId: String, quality: Int) extends ResourceKey with Logging {
    def outputPath: String = s"${resourceId}_${quality}p.webp"

    override def create(config: LocalDirectoryConfig, relativePath: String): IO[Path] = {

      val outputFile = config.resourcePath.resolve(outputPath)

      logger.debug(s"Creating image thumbnail for ${relativePath}")

      ImageMagick.resizeImage(
        inputFile = config.mediaPath.resolve(relativePath),
        outputFile = Some(outputFile),
        scaleHeight = quality
      ).map(_ => outputFile)
    }
  }

  case class FragmentKey(resourceId: String, range: (Long, Long), quality: Int) extends ResourceKey with Logging {
    def outputPath: String = s"${resourceId}_${range._1}-${range._2}_${quality}p.mp4"

    def create(config: LocalDirectoryConfig, relativePath: String): IO[Path] = {

      logger.debug(s"Creating fragment for ${relativePath} with range ${range}")
      val inputFile = config.mediaPath.resolve(relativePath)
      val outputFile = config.resourcePath.resolve(outputPath)

      FFMpeg.transcodeToMp4(
        inputFile = inputFile,
        range = range,
        crf = 23,
        scaleHeight = Some(quality),
        outputFile = Some(outputFile),
      )
    }
  }
}

package nl.amony.service.resources.local

import cats.effect.IO
import nl.amony.lib.ffmpeg.FFMpeg
import nl.amony.lib.magick.ImageMagick
import nl.amony.service.resources.ResourceConfig.LocalDirectoryConfig
import scribe.Logging

import java.nio.file.Path

object LocalResourceOperations {

  sealed trait ResourceOp {

    def outputFilename: String
    def create(config: LocalDirectoryConfig, relativePath: String): IO[Path]
  }

  case class VideoThumbnailOp(resourceId: String, timestamp: Long, quality: Int) extends ResourceOp with Logging {
    def outputFilename: String = s"${resourceId}_${timestamp}_${quality}p.webp"

    override def create(config: LocalDirectoryConfig, relativePath: String): IO[Path] = {

      val outputFile = config.writePath.resolve(outputFilename)
      logger.info(s"Creating thumbnail for ${relativePath} with timestamp ${timestamp}")

      FFMpeg.createThumbnail(
        inputFile = config.resourcePath.resolve(relativePath),
        timestamp = timestamp,
        outputFile = Some(outputFile),
        scaleHeight = Some(quality)
      ).map(_ => outputFile)
    }
  }

  case class ImageThumbnailOp(resourceId: String, width: Option[Int], height: Option[Int]) extends ResourceOp with Logging {
    def outputFilename: String = s"${resourceId}_${height.getOrElse("")}p.webp"

    override def create(config: LocalDirectoryConfig, relativePath: String): IO[Path] = {

      val outputFile = config.writePath.resolve(outputFilename)

      logger.info(s"Creating image thumbnail for ${relativePath}")

      ImageMagick.resizeImage(
        inputFile = config.resourcePath.resolve(relativePath),
        outputFile = Some(outputFile),
        width = width,
        height = height
      ).map(_ => outputFile)
    }
  }

  case class VideoFragmentOp(resourceId: String, range: (Long, Long), height: Int) extends ResourceOp with Logging {
    def outputFilename: String = s"${resourceId}_${range._1}-${range._2}_${height}p.mp4"

    def create(config: LocalDirectoryConfig, relativePath: String): IO[Path] = {

      logger.debug(s"Creating fragment for ${relativePath} with range ${range}")
      val inputFile = config.resourcePath.resolve(relativePath)
      val outputFile = config.writePath.resolve(outputFilename)

      FFMpeg.transcodeToMp4(
        inputFile = inputFile,
        range = range,
        crf = 23,
        scaleHeight = Some(height),
        outputFile = Some(outputFile),
      )
    }
  }
}

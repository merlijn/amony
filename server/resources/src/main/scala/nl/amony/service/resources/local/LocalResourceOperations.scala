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
    def createFile(inputFile: Path, outputDir: Path): IO[Path]
  }
  
  val NoOp = new ResourceOp {
    override def outputFilename: String = ""
    override def createFile(inputFile: Path, outputDir: Path): IO[Path] = IO.raiseError(new Exception("NoOp"))
  }

  case class VideoThumbnailOp(resourceId: String, timestamp: Long, quality: Int) extends ResourceOp with Logging {
    def outputFilename: String = s"${resourceId}_${timestamp}_${quality}p.webp"

    override def createFile(inputFile: Path, outputDir: Path): IO[Path] = {

      val outputFile = outputDir.resolve(outputFilename)
      logger.debug(s"Creating thumbnail for ${inputFile} with timestamp ${timestamp}")

      FFMpeg.createThumbnail(
        inputFile = inputFile,
        timestamp = timestamp,
        outputFile = Some(outputFile),
        scaleHeight = Some(quality)
      ).map(_ => outputFile)
    }
  }

  case class ImageThumbnailOp(resourceId: String, width: Option[Int], height: Option[Int]) extends ResourceOp with Logging {
    def outputFilename: String = s"${resourceId}_${height.getOrElse("")}p.webp"

    override def createFile(inputFile: Path, outputDir: Path): IO[Path] = {

      val outputFile = outputDir.resolve(outputFilename)

      logger.debug(s"Creating image thumbnail for ${inputFile}")

      ImageMagick.resizeImage(
        inputFile = inputFile,
        outputFile = Some(outputFile),
        width = width,
        height = height
      ).map(_ => outputFile)
    }
  }

  case class VideoFragmentOp(resourceId: String, range: (Long, Long), height: Int) extends ResourceOp with Logging {
    def outputFilename: String = s"${resourceId}_${range._1}-${range._2}_${height}p.mp4"

    def createFile(inputFile: Path, outputDir: Path): IO[Path] = {

      logger.debug(s"Creating fragment for ${inputFile} with range ${range}")
      val outputFile = outputDir.resolve(outputFilename)

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

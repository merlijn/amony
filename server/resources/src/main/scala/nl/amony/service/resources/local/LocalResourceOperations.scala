package nl.amony.service.resources.local

import cats.effect.IO
import nl.amony.lib.ffmpeg.FFMpeg
import nl.amony.lib.magick.ImageMagick
import nl.amony.service.resources.*
import nl.amony.service.resources.api.operations.*
import nl.amony.service.resources.api.{ImageMeta, ResourceMeta, VideoMeta}
import scribe.Logging

import java.nio.file.Path

object LocalResourceOperations {

  def createResource(inputFile: Path, inputMeta: ResourceMeta, outputDir: Path, operation: LocalResourceOp): IO[Path] =
    val outputFile = outputDir.resolve(operation.outputFilename)

    if (!operation.isCompatibleWith(inputMeta))
      IO.raiseError(new Exception(s"Operation ${operation} is not compatible with ${inputMeta}"))
    else
      operation.createFile(inputFile, outputDir).memoize.flatten

  sealed trait LocalResourceOp {

    def isCompatibleWith(meta: ResourceMeta): Boolean = true
    def outputFilename: String
    def createFile(inputFile: Path, outputDir: Path): IO[Path]
  }
  
  object LocalResourceOp {
    def apply(parentId: String, operation: ResourceOperation): LocalResourceOp = operation match
      case VideoFragment(width, height, start, end, quality) => VideoFragmentOp(parentId, (start, end), height.get)
      case VideoThumbnail(width, height, quality, timestamp) => VideoThumbnailOp(parentId, timestamp, height.get)
      case ImageThumbnail(width, height, quality)            => ImageThumbnailOp(parentId, width, height)
      case ResourceOperation.Empty                           => NoOp
  }
  
  val NoOp = new LocalResourceOp {
    override def isCompatibleWith(meta: ResourceMeta): Boolean = false
    override def outputFilename: String = ""
    override def createFile(inputFile: Path, outputDir: Path): IO[Path] = IO(inputFile)
  }
  
  case class VideoThumbnailOp(resourceId: String, timestamp: Long, quality: Int) extends LocalResourceOp with Logging {
    def outputFilename: String = s"${resourceId}_${timestamp}_${quality}p.webp"

    override def isCompatibleWith(meta: ResourceMeta): Boolean = meta.isInstanceOf[VideoMeta]

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

  case class ImageThumbnailOp(resourceId: String, width: Option[Int], height: Option[Int]) extends LocalResourceOp with Logging {
    def outputFilename: String = s"${resourceId}_${height.getOrElse("")}p.webp"

    override def isCompatibleWith(meta: ResourceMeta): Boolean = meta.isInstanceOf[ImageMeta]

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

  case class VideoFragmentOp(resourceId: String, range: (Long, Long), height: Int) extends LocalResourceOp with Logging {
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

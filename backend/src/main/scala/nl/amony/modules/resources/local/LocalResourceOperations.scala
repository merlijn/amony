package nl.amony.modules.resources.local

import java.nio.file.Path

import cats.effect.IO
import scribe.Logging

import nl.amony.lib.ffmpeg.FFMpeg
import nl.amony.lib.magick.ImageMagick
import nl.amony.modules.resources.*
import nl.amony.modules.resources.api.*

object LocalResourceOperations {

  def createResource(inputFile: Path, inputMeta: ResourceInfo, outputDir: Path, operation: LocalResourceOp): IO[Path] =
    operation.validate(inputMeta) match
      case Left(error) => IO.raiseError(new Exception(error))
      case Right(_)    => operation.createFile(inputFile, outputDir).memoize.flatten

  sealed trait LocalResourceOp {

    def contentType: String
    def validate(meta: ResourceInfo): Either[String, Unit] = Right(())
    def outputFilename: String
    def createFile(inputFile: Path, outputDir: Path): IO[Path]
  }

  object LocalResourceOp {
    def apply(parentId: String, operation: ResourceOperation): LocalResourceOp = operation match
      case VideoFragment(width, height, start, end, quality) => VideoFragmentOp(parentId, (start, end), height.get)
      case VideoThumbnail(width, height, quality, timestamp) => VideoThumbnailOp(parentId, timestamp, height.get)
      case ImageThumbnail(width, height, quality)            => ImageThumbnailOp(parentId, width, height)
  }

  case class VideoThumbnailOp(resourceId: String, timestamp: Long, quality: Int) extends LocalResourceOp with Logging {

    override def contentType = "image/webp"

    def outputFilename: String = s"${resourceId}_${timestamp}_${quality}p.webp"

    override def validate(info: ResourceInfo): Either[String, Unit] = info.contentMeta.map(_.properties) match {
      case Some(video: VideoProperties) =>
        for { _ <- Either.cond(timestamp > 0 && timestamp < video.durationInMillis, (), "Timestamp is out of bounds") } yield ()
      case other                        => Left("Wrong content type, expected video, got: " + other)
    }

    override def createFile(inputFile: Path, outputDir: Path): IO[Path] = {

      logger.debug(s"Creating thumbnail for $inputFile at timestamp $timestamp")
      val outputFile = outputDir.resolve(outputFilename)

      FFMpeg.createThumbnail(inputFile = inputFile, timestamp = timestamp, outputFile = Some(outputFile), scaleHeight = Some(quality))
        .map(_ => outputFile)
    }
  }

  case class ImageThumbnailOp(resourceId: String, width: Option[Int], height: Option[Int]) extends LocalResourceOp with Logging {

    override def contentType = "image/webp"

    def outputFilename: String = s"${resourceId}_${height.getOrElse("")}p.webp"

    val minHeight = 64
    val minWidth  = 64
    val maxHeight = 4096
    val maxWidth  = 4096

    override def validate(info: ResourceInfo): Either[String, Unit] = {

      for {
        _ <- Either.cond(height.getOrElse(Int.MaxValue) > minHeight, (), "Height too small")
        _ <- Either.cond(width.getOrElse(Int.MaxValue) > minWidth, (), "Width too small")
        _ <- Either.cond(height.getOrElse(0) < maxHeight, (), "Height too large")
        _ <- Either.cond(width.getOrElse(0) < maxWidth, (), "Width too large")
      } yield ()
    }

    override def createFile(inputFile: Path, outputDir: Path): IO[Path] = {

      val outputFile = outputDir.resolve(outputFilename)

      logger.debug(s"Creating image thumbnail for $inputFile")

      ImageMagick.resizeImage(inputFile = inputFile, outputFile = Some(outputFile), width = width, height = height).map(_ => outputFile)
    }
  }

  case class VideoFragmentOp(resourceId: String, range: (Long, Long), height: Int) extends LocalResourceOp with Logging {

    override def contentType = "video/mp4"

    val minHeight         = 120
    val maxHeight         = 4096
    val minLengthInMillis = 1000
    val maxLengthInMillis = 60000

    def outputFilename: String = s"${resourceId}_${range._1}-${range._2}_${height}p.mp4"

    override def validate(info: ResourceInfo): Either[String, Unit] = {
      info.contentMeta.map(_.properties) match {
        case Some(video: VideoProperties) =>
          val (start, end) = range
          val duration     = end - start
          for {
            _ <- Either.cond(height > minHeight || height < maxHeight, (), "Height out of bounds")
            _ <- Either.cond(start >= 0, (), "Start time is negative")
            _ <- Either.cond(end > start, (), "End time is before start time")
            _ <- Either.cond(duration > minLengthInMillis, (), "Duration too short")
            _ <- Either.cond(duration < maxLengthInMillis, (), "Duration too long")
          } yield ()
        case other                        => Left("Wrong content type, expected video, got: " + other)
      }
    }

    def createFile(inputFile: Path, outputDir: Path): IO[Path] = {

      logger.debug(s"Creating video fragment for $inputFile with range $range")
      val outputFile = outputDir.resolve(outputFilename)

      FFMpeg.transcodeToMp4(inputFile = inputFile, range = range, scaleHeight = Some(height), outputFile = Some(outputFile))
    }
  }
}

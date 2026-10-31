package nl.amony.platform.subprocess.ffmpeg.tasks

import java.net.URI
import java.nio.file.Path

import cats.effect.IO

import nl.amony.platform.subprocess.ffmpeg.FFMpeg
import nl.amony.platform.subprocess.ffmpeg.FFMpeg.{formatTime, scaleFilter}
import nl.amony.platform.subprocess.{Command, ProcessRunner}

trait CreateThumbnail:

  self: ProcessRunner =>

  def createThumbnail(
    input: URI,
    timestamp: Long,
    outputFile: Path,
    scaleWidth: Option[Int]  = None,
    scaleHeight: Option[Int] = None,
    codecArgs: List[String]  = Nil
  ): IO[Int] =

    val output = outputFile.toAbsolutePath.normalize.toString

    // format: off
    val args = List(
      "-ss",      formatTime(timestamp),
      "-i",       FFMpeg.inputArg(input)
    ) ++ scaleFilter(scaleWidth, scaleHeight).toList.flatMap(filter => List("-vf", filter)) ++ codecArgs ++
      List(
        "-vframes", "1",
        "-v",       "quiet",
        "-y",       output
      )
    // format: on

    runIgnoreOutput("ffmpeg-create-video-thumbnail", Command("ffmpeg", args), settings.thumbnailTimeout)

  /** Encodes a still image at the given pinned dimension; the output format follows the output extension. */
  def resizeImage(
    input: URI,
    outputFile: Path,
    width: Option[Int]      = None,
    height: Option[Int]     = None,
    codecArgs: List[String] = Nil
  ): IO[Int] = {
    val output = outputFile.toAbsolutePath.normalize.toString

    // format: off
    val args = List("-i", FFMpeg.inputArg(input)) ++
      scaleFilter(width, height).toList.flatMap(filter => List("-vf", filter)) ++ codecArgs ++
      List(
        "-vframes", "1",
        "-v",       "quiet",
        "-y",       output
      )
    // format: on

    runIgnoreOutput("ffmpeg-create-image-thumbnail", Command("ffmpeg", args), settings.thumbnailTimeout)
  }

  def streamThumbnail(input: URI, timestamp: Long, scaleHeight: Int): fs2.Stream[IO, Byte] = {
  
    // format: off
    val args = List(
      "-ss", formatTime(timestamp),
      "-i", FFMpeg.inputArg(input),
      "-vcodec", "webp",
      "-vf", s"scale=-2:$scaleHeight",
      "-vframes", "1",
      "-f", "image2pipe",
      "-"
    )
    // format: on

    streamProcess("ffmpeg-stream-video-thumbnail", Command("ffmpeg", args), settings.thumbnailTimeout)(_.stdout)
  }

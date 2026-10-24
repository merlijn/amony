package nl.amony.platform.subprocess.ffmpeg.tasks

import java.nio.file.Path

import cats.effect.IO

import nl.amony.platform.files.*
import nl.amony.platform.files.FileUtil.stripExtension
import nl.amony.platform.subprocess.ffmpeg.FFMpeg.{formatTime, scaleFilter}
import nl.amony.platform.subprocess.{Command, ProcessRunner}

trait CreateThumbnail:

  self: ProcessRunner =>

  def createThumbnail(
    inputFile: Path,
    timestamp: Long,
    outputFile: Option[Path],
    scaleWidth: Option[Int]  = None,
    scaleHeight: Option[Int] = None,
    codecArgs: List[String]  = Nil
  ): IO[Int] =

    val input  = inputFile.absoluteFileName()
    val output = outputFile.map(_.absoluteFileName()).getOrElse(s"${stripExtension(input)}.webp")

    // format: off
    val args = List(
      "-ss",      formatTime(timestamp),
      "-i",       input
    ) ++ scaleFilter(scaleWidth, scaleHeight).toList.flatMap(filter => List("-vf", filter)) ++ codecArgs ++
      List(
        "-vframes", "1",
        "-v",       "quiet",
        "-y",       output
      )
    // format: on

    runIgnoreOutput("ffmpeg-create-video-thumbnail", Command("ffmpeg", args))

  /** Encodes a still image at the given pinned dimension; the output format follows the output extension. */
  def resizeImage(
    inputFile: Path,
    outputFile: Option[Path],
    width: Option[Int]      = None,
    height: Option[Int]     = None,
    codecArgs: List[String] = Nil
  ): IO[Int] = {
    val input  = inputFile.absoluteFileName()
    val output = outputFile.map(_.absoluteFileName()).getOrElse(s"${stripExtension(input)}.webp")

    // format: off
    val args = List("-i", input) ++
      scaleFilter(width, height).toList.flatMap(filter => List("-vf", filter)) ++ codecArgs ++
      List(
        "-vframes", "1",
        "-v",       "quiet",
        "-y",       output
      )
    // format: on

    runIgnoreOutput("ffmpeg-create-image-thumbnail", Command("ffmpeg", args))
  }

  def streamThumbnail(inputFile: Path, timestamp: Long, scaleHeight: Int): fs2.Stream[IO, Byte] = {
  
    // format: off
    val args = List(
      "-ss", formatTime(timestamp),
      "-i", inputFile.toString,
      "-vcodec", "webp",
      "-vf", s"scale=-2:$scaleHeight",
      "-vframes", "1",
      "-f", "image2pipe",
      "-"
    )
    // format: on

    fs2.Stream.force(useProcess("ffmpeg-stream-video-thumbnail", Command("ffmpeg", args))(p => IO(p.stdout)))
  }

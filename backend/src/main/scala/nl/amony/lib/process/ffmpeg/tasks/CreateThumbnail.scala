package nl.amony.lib.process.ffmpeg.tasks

import java.nio.file.Path

import cats.effect.IO
import scribe.Logging

import nl.amony.lib.files.*
import nl.amony.lib.files.FileUtil.stripExtension
import nl.amony.lib.process.ffmpeg.FFMpeg.formatTime
import nl.amony.lib.process.{Command, ProcessRunner}

trait CreateThumbnail:

  self: ProcessRunner =>

  def createThumbnail(inputFile: Path, timestamp: Long, outputFile: Option[Path], scaleHeight: Option[Int]): IO[Int] =

    val input  = inputFile.absoluteFileName()
    val output = outputFile.map(_.absoluteFileName()).getOrElse(s"${stripExtension(input)}.webp")

    // format: off
    val args = List(
      "-ss",      formatTime(timestamp),
      "-i",       input
    ) ++ scaleHeight.toList.flatMap(height => List("-vf",  s"scale=-2:$height")) ++
      List(
        "-quality", "80", // 1 - 31 (best-worst) for jpeg, 1-100 (worst-best) for webp
        "-vframes", "1",
        "-v",       "quiet",
        "-y",       output
      )
    // format: on

    runIgnoreOutput("ffmpeg-create-video-thumbnail", Command("ffmpeg", args))

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

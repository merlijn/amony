package nl.amony.lib.ffmpeg.tasks

import monix.eval.Task
import nl.amony.lib.files.FileUtil.stripExtension
import nl.amony.lib.ffmpeg.FFMpeg.{formatTime, runSync}
import nl.amony.lib.files.PathOps
import scribe.Logging

import java.nio.file.Path

trait CreateThumbnail extends Logging {

  self: ProcessRunner with FFProbe =>

  def createThumbnail(
    inputFile: Path,
    timestamp: Long,
    outputFile: Option[Path],
    scaleHeight: Option[Int]): Task[Unit] = {

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

      runIgnoreOutput(cmds = "ffmpeg" :: args, useErrorStream = true)

//      case e: Exception =>
//        logger.warn(
//          s"Failed to create thumbnail for inputFile: ${inputFile}, timestamp: ${formatTime(timestamp)}, outputFile: ${outputFile}, scaleHeight: ${scaleHeight}", e
//        )
  }
}

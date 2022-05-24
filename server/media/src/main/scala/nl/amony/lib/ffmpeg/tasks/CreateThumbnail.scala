package nl.amony.lib.ffmpeg.tasks

import nl.amony.lib.FileUtil.stripExtension
import nl.amony.lib.ffmpeg.FFMpeg.{formatTime, runSync}
import nl.amony.lib.files.PathOps
import scribe.Logging

import java.nio.file.Path

trait CreateThumbnail extends Logging {

  def createThumbnail(
        inputFile: Path,
        timestamp: Long,
        outputFile: Option[Path],
        scaleHeight: Option[Int]
      ): Unit = {

    try {
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

      runSync(useErrorStream = true, cmds = "ffmpeg" :: args)
    } catch {
      case e: Exception =>
        logger.warn(
          s"Failed to create thumbnail for inputFile: ${inputFile}, timestamp: ${formatTime(timestamp)}, outputFile: ${outputFile}, scaleHeight: ${scaleHeight}", e
        )
    }
  }
}

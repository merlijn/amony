package com.github.merlijn.kagera.lib

import better.files.File
import com.github.merlijn.kagera.lib.FileUtil.stripExtension
import scribe.Logging

import java.nio.file.Path
import java.time.Duration

object FFMpeg extends Logging {

  case class Probe(id: String, fileName: String, duration: Long, resolution: (Int, Int))

  def ffprobe(file: Path): Probe = {

    val fileName = file.toAbsolutePath.toString
    val output   = run("ffprobe", fileName)

    val pattern = raw"Duration:\s(\d{2}):(\d{2}):(\d{2})".r.unanchored
    val res     = raw"Stream #0.*,\s(\d{2,})x(\d{2,})".r.unanchored

    val (w, h) = output match {
      case res(w, h) =>
        (w.toInt, h.toInt)
      case _ =>
        logger.warn("Could not extract resolution")
        (0, 0)
    }

    val duration: Long = output match {
      case pattern(hours, minutes, seconds) =>
        hours.toInt * 60 * 60 * 1000 +
          minutes.toInt * 60 * 1000 +
          seconds.toInt * 1000
    }

    val hash = FileUtil.fakeHash(file)

    Probe(hash, fileName, duration, (w, h))
  }

  private def seek(timestamp: Long): String = {

    val duration = Duration.ofMillis(timestamp)

    val hours   = "%02d".format(duration.toHoursPart)
    val minutes = "%02d".format(duration.toMinutesPart)
    val seconds = "%02d".format(duration.toSecondsPart)
    val millis  = "%03d".format(duration.toMillisPart)

    s"$hours:$minutes:$seconds.$millis"
  }

  def createGif(inputFile: String, timestamp: Long, outputFile: String): Unit = {

    // format: off
    run(
      "ffmpeg",
      "-ss", seek(timestamp),
      "-t", "2",
      "-i", inputFile,
      "-vf", "fps=8,scale=320:-1:flags=lanczos,split[s0][s1];[s0]palettegen[p];[s1][p]paletteuse",
      "-loop", "0",
      outputFile
    )
    // format: on
  }

  def createWebP(inputFile: String, timestamp: Long, outputFile: Option[String]): Unit = {

    // format: off
    run(
      "ffmpeg",
      "-ss", seek(timestamp),
      "-t", "3",
      "-i", inputFile,
      "-vf", "fps=8,scale=320:-1:flags=lanczos",
      "-vcodec", "libwebp",
      "-lossless", "0",
      "-compression_level", "3",
      "-loop", "0",
      "-q:v", "70",
      "-preset", "picture",
      "-an",
       "-vsync", "0",
      outputFile.getOrElse(s"${stripExtension(inputFile)}.webp")
    )
    // format: on
  }

  def writeThumbnail(
      inputFile: String,
      timestamp: Long,
      outputFile: Option[String],
      overwrite: Boolean = false
  ): Unit = {

    val thumbnailFile = File(outputFile.getOrElse(s"${stripExtension(inputFile)}.webp"))

    if (overwrite && thumbnailFile.exists)
      thumbnailFile.delete()

    if (!thumbnailFile.exists) {

      // format: off
      run(
        s"ffmpeg",
        "-ss", seek(timestamp),
        "-i", inputFile,
        "-vframes", "1",
        thumbnailFile.path.toAbsolutePath.toString
      )
      // format: on
    }
  }

  def run(cmds: String*): String = {

    logger.debug(s"Running command: ${cmds.mkString(",")}")

    val r        = Runtime.getRuntime
    val p        = r.exec(cmds.toArray)
    val is       = p.getErrorStream
    val output   = scala.io.Source.fromInputStream(is).mkString
    val exitCode = p.waitFor()

    if (exitCode != 0)
      logger.warn(s"""Non zero exit code for command: ${cmds.mkString(",")} \n""" + output)

    output
  }
}

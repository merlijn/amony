package com.github.merlijn.webapp.lib

import better.files.File
import scribe.Logging

import java.nio.file.Path
import java.time.Duration

object FFMpeg extends Logging {

  case class Probe(id: String,
                   fileName: String,
                   duration: Long,
                   resolution: (Int, Int))

  def ffprobe(file: Path): Probe = {

    val fileName = file.toAbsolutePath.toString
    val output = run("ffprobe", fileName)

    val pattern = raw"Duration:\s(\d{2}):(\d{2}):(\d{2})".r.unanchored
    val res = raw"Stream #0.*,\s(\d{2,})x(\d{2,})".r.unanchored

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

  def writeThumbnail(inputFile: String, time: Long, outputFile: Option[String], overwrite: Boolean = false): Unit = {

    val baseFilename = inputFile.substring(0, inputFile.lastIndexOf('.'))
    val thumbnailFile = File(outputFile.getOrElse(s"$baseFilename.jpeg"))

    if (overwrite && thumbnailFile.exists)
      thumbnailFile.delete()

    if (!thumbnailFile.exists) {

      val timestamp = Duration.ofMillis(time)
      val ss = s"${timestamp.toHoursPart}:${timestamp.toMinutesPart}:${timestamp.toSecondsPart}"

      logger.info(s"Creating thumbnail at $ss for $inputFile")

      run(s"ffmpeg", "-ss", ss, "-i", inputFile, "-vframes", "1", thumbnailFile.path.toAbsolutePath.toString)
    }
  }

  def run(cmds: String*): String = {

    val r = Runtime.getRuntime
    val p = r.exec(cmds.toArray)
    val is = p.getErrorStream
    val output = scala.io.Source.fromInputStream(is).mkString
    val exitCode = p.waitFor()

    if (exitCode != 0)
      logger.warn(s"""Non zero exit code for command: ${cmds.mkString(",")} \n""" + output)

    output
  }
}

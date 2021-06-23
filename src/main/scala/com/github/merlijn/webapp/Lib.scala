package com.github.merlijn.webapp

import better.files._

import java.time.Duration

object Lib extends Logging {

  case class Info(
    id: String,
    fileName: String,
    duration: Long,
    resolution: (Int, Int)
  )

  val extensions = Seq("mp4", "webm")

  def index(path: String, max: Int): Seq[Info] = {
    val dir = File(path)
    val matches: Iterator[File] = dir.listRecursively.filter { f =>
      extensions.exists(ext => f.name.endsWith(s".$ext")) && !f.name.startsWith(".")
    }

    matches.take(max).map { f =>

      logger.info(s"Processing: ${f.path.toAbsolutePath}")

      val info = ffprobe(f)

      info
    }.toSeq
  }

  def ffprobe(file: File): Info = {

    val fileName = file.path.toAbsolutePath.toString
    val output = run("ffprobe", fileName)

    val pattern = raw"Duration:\s(\d{2}):(\d{2}):(\d{2})".r.unanchored
    val res = raw"Stream #0.*,\s(\d{2,})x(\d{2,})".r.unanchored

    val (w, h) = output match {
      case res(w, h) =>
        (w.toInt, h.toInt)
      case _         =>
        logger.info("no match")
        (0, 0)
    }

    val duration: Long = output match {
      case pattern(hours, minutes, seconds) =>
        hours.toInt *  60 * 60 * 1000 +
        minutes.toInt * 60 * 1000 +
        seconds.toInt * 1000
    }

    Info(fileName.hashCode.toHexString, fileName, duration, (w, h))
  }

  def writeThumbnail(fileName: String, time: Long, destination: Option[String]): Unit = {

    val baseFilename = fileName.substring(0, fileName.lastIndexOf('.'))
    val thumbnailFile = File(destination.getOrElse(s"$baseFilename.jpeg"))

    if (!thumbnailFile.exists) {

      logger.info(s"Creating thumbnail for $fileName")

      val timestamp = Duration.ofMillis(time)
      val ss = s"${timestamp.toHoursPart}:${timestamp.toMinutesPart}:${timestamp.toSecondsPart}"

      run(s"ffmpeg", "-ss", ss, "-i", fileName, "-vframes", "1", thumbnailFile.path.toAbsolutePath.toString)
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

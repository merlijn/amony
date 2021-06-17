package com.github.merlijn.webapp

import better.files.*
import File.*

import java.io.{File as JFile}
import java.time.Duration

object Lib {

  case class Info(
    fileName: String,
    duration: Long
  )

  def index(path: String): Seq[Info] = {
    val dir = File(path)
    val matches: Iterator[File] = dir.listRecursively.filter(_.name.endsWith(".mp4"))

    matches.map { f =>

      val fileName = s"$dir/${f.name}"
      val info = ffprobe(fileName)

      info
    }.toSeq
  }

  def ffprobe(fileName: String): Info = {

    val output = run("ffprobe", fileName)
    val pattern = raw"Duration:\s(\d{2}):(\d{2}):(\d{2})".r.unanchored

    val duration: Long = output match {
      case pattern(hours, minutes, seconds) =>
        hours.toInt *  60 * 60 * 1000 +
        minutes.toInt * 60 * 1000 +
        seconds.toInt * 1000
    }

    Info(fileName, duration)
  }

  def writeThumbnail(fileName: String, time: Long): Unit = {

    val timestamp = Duration.ofMillis(time)
    val ss = s"${timestamp.toHoursPart}:${timestamp.toMinutesPart}:${timestamp.toSecondsPart}"

    val baseFilename = fileName.substring(0, fileName.lastIndexOf('.'))

    run(s"ffmpeg", "-ss", ss, "-i", fileName, "-vframes", "1", s"$baseFilename.jpeg")
  }

  def run(cmds: String*): String = {

    import java.io.BufferedReader
    import java.io.InputStreamReader

    val r = Runtime.getRuntime
    val p = r.exec(cmds.toArray)
    val is = p.getErrorStream
    val output = scala.io.Source.fromInputStream(is).mkString
    val exitCode = p.waitFor()

    if (exitCode != 0)
      println("non zero exit code: \n" + output)

    output
  }
}

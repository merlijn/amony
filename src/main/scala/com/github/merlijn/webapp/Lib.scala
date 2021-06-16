package com.github.merlijn.webapp

import better.files.*
import File.*
import java.io.{File as JFile}
import Model.*

object Lib {

  case class Info(
    duration: String,
    fps: Double
  )

  def index(): Unit = {
    val dir = "/Users/merlijn" / "Downloads"
    val matches: Iterator[File] = dir.listRecursively.filter(_.name.endsWith(".mp4")) //.filter(f => f.extension == Some(".java") || f.extension == Some(".scala")) //dir.glob("**/*.{mp4,wmv}")

    matches.foreach { f =>

      val fileName = s"$dir/${f.name}"
      println(ffprobe(fileName).toString)
      writeThumbnail(fileName, "00:05:12")
    }
  }

  def ffprobe(fileName: String): Info = {

    val command = s"ffprobe $fileName"

    val output = run(command)
    val pattern = raw"Duration:\s(\d{2}):(\d{2}):(\d{2})".r
//    val pattern = "Duration:\\s(\\d\\d:\\d\\d:\\d\\d)".r
    val duration = pattern.findFirstIn(output).get

    Info(duration, 29d)
  }

  def writeThumbnail(fileName: String, time: String): Unit = {

    val baseFilename = fileName.substring(0, fileName.lastIndexOf('.'))

    run(s"""ffmpeg -ss $time -i $fileName -vframes 1 $baseFilename.jpeg""")
  }

  def run(command: String): String = {

    import java.io.BufferedReader
    import java.io.InputStreamReader

    // A Runtime object has methods for dealing with the OS
    val r = Runtime.getRuntime
    val p = r.exec(command)
    val is = p.getErrorStream
    val output = scala.io.Source.fromInputStream(is).mkString
    val exitCode = p.waitFor()

    if (exitCode != 0)
      println("non zero exit code: \n" + output)

    output
  }
}

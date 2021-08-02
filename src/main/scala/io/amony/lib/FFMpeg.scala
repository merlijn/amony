package io.amony.lib

import io.amony.lib.FileUtil.{PathOps, stripExtension}
import scribe.Logging

import java.nio.file.Path
import java.time.Duration

object FFMpeg extends Logging {

  val previewSize = 640

  case class Probe(duration: Long,
                   resolution: (Int, Int),
                   fps: Double)

  val pattern = raw"Duration:\s(\d{2}):(\d{2}):(\d{2})".r.unanchored
  val res     = raw"Stream #0.*,\s(\d{2,})x(\d{2,})".r.unanchored

  val fpsPattern = raw"Stream #0.*,\s([\w\.]+)\sfps".r.unanchored

  val streamPattern = """\s*Stream #.*(Video.*)""".r.unanchored

  def extractFps(ffprobeOutput: String, hint: String): Option[Double] = {
    ffprobeOutput match {
      case fpsPattern(fps) => Some(fps.toDouble)
      case _ =>
        logger.warn(s"Failed to extract fps info from '$hint''")
        None
    }
  }

  val isStreamablePattern = """IsStreamable.*: Yes""".r.unanchored

  def isStreamable(file: Path): Boolean = {

    val out = FFMpeg.run(
      useErrorStream = false,
      cmds = List("mediainfo", "-f", file.absoluteFileName())
    )

    isStreamablePattern.findFirstIn(out).isDefined
  }

  def ffprobe(file: Path): Probe = {

    val fileName = file.toAbsolutePath.toString
    val output   = run(useErrorStream = true, cmds = List("ffprobe", fileName))

    val (w, h) = output match {
      case res(w, h) =>
        (w.toInt, h.toInt)
      case _ =>
        logger.warn(s"Failed to extract fps info from '$file'")
        (0, 0)
    }

    val duration: Long = output match {
      case pattern(hours, minutes, seconds) =>
        hours.toInt * 60 * 60 * 1000 +
          minutes.toInt * 60 * 1000 +
          seconds.toInt * 1000
    }

    val fps = extractFps(output, file.toString).getOrElse(0d)

    Probe(duration, (w, h), fps)
  }

  private def formatTime(timestamp: Long): String = {

    val duration = Duration.ofMillis(timestamp)

    val hours   = "%02d".format(duration.toHoursPart)
    val minutes = "%02d".format(duration.toMinutesPart)
    val seconds = "%02d".format(duration.toSecondsPart)
    val millis  = "%03d".format(duration.toMillisPart)

    s"$hours:$minutes:$seconds.$millis"
  }

  def addFastStart(video: Path): Path = {

    val out = s"${video.stripExtension}-faststart.mp4"

    logger.info(s"Adding faststart at: $out")

    // format: off
    run(
      useErrorStream = true,
      cmds = List(
        "ffmpeg",
        "-i",    video.absoluteFileName(),
        "-c",   "copy",
        "-map", "0",
        "-movflags", "+faststart",
        "-y",    out
      )
    )
    // format: on

    Path.of(out)
  }

  def createGif(inputFile: String, timestamp: Long, outputFile: String): Unit = {

    // format: off
    run(
      useErrorStream = true,
      cmds = List(
        "ffmpeg",
        "-ss",   formatTime(timestamp),
        "-t",    "3",
        "-i",    inputFile,
        "-vf",   "fps=8,scale=320:-1:flags=lanczos,split[s0][s1];[s0]palettegen[p];[s1][p]paletteuse",
        "-loop", "0",
        "-y",    outputFile
      )
    )
    // format: on
  }

  def createWebP(
      inputFile: String,
      timestamp: Long,
      durationInSeconds: Int = 3,
      outputFile: Option[String] = None
  ): Unit = {

    // format: off
    run(
      useErrorStream = true,
      cmds = List(
        "ffmpeg",
        "-ss",       formatTime(timestamp),
        "-t",        durationInSeconds.toString,
        "-i",        inputFile,
        "-vf",       s"fps=8,scale=$previewSize:trunc(ow/a/2)*2:flags=lanczos",
        "-vcodec",   "libwebp",
        "-lossless", "0",
        "-loop",     "0",
        "-q:v",      "80",
        "-preset",   "picture",
        "-vsync",    "0",
        "-an",
        "-y", outputFile.getOrElse(s"${stripExtension(inputFile)}.webp")
      )
    )
    // format: on
  }

  def createMp4(
      inputFile: String,
      from: Long,
      to: Long,
      outputFile: Option[String] = None
  ): Unit = {
    // format: off
      run(
        useErrorStream = true,
        cmds = List(
          "ffmpeg",
          "-ss",  formatTime(from),
          "-to",  formatTime(to),
          "-i",   inputFile,
          "-vf",  s"scale=$previewSize:trunc(ow/a/2)*2", // scale="720:trunc(ow/a/2)*2"
          "-q:v", "80",
          "-an",
          "-y",   outputFile.getOrElse(s"${stripExtension(inputFile)}.mp4")
          )
        )
    // format: on
  }

  def writeThumbnail(
      inputFile: String,
      timestamp: Long,
      outputFile: Option[String]
  ): Unit = {

    // format: off
    run(
      useErrorStream = true,
      cmds = List(
        s"ffmpeg",
        "-ss",      formatTime(timestamp),
        "-i",       inputFile,
        "-vf",      s"scale=$previewSize:-1",
        "-q:v",     "80", // 1 - 30 (best-worst) for jpeg, 1-100 (worst-best) for webp
        "-vframes", "1",
        "-y",       outputFile.getOrElse(s"${stripExtension(inputFile)}.webp")
      )
    )
    // format: on
  }

  def run(useErrorStream: Boolean, cmds: Seq[String]): String = {

    logger.debug(s"Running command: ${cmds.mkString(",")}")

    val runtime  = Runtime.getRuntime
    val process  = runtime.exec(cmds.toArray)
    val is       = if (useErrorStream) process.getErrorStream else process.getInputStream
    val output   = scala.io.Source.fromInputStream(is).mkString
    val exitCode = process.waitFor()

    if (exitCode != 0)
      logger.warn(s"""Non zero exit code for command: ${cmds.mkString(",")} \n""" + output)

    output
  }
}

package nl.amony.lib.ffmpeg

import better.files.File
import monix.eval.Task
import nl.amony.lib.FileUtil.PathOps
import nl.amony.lib.FileUtil.stripExtension
import nl.amony.lib.ffmpeg.Model.ProbeDebugOutput
import nl.amony.lib.ffmpeg.Model.ProbeOutput
import scribe.Logging

import java.io.InputStream
import java.nio.file.{Files, Path, Paths}
import java.time.Duration
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

object FFMpeg extends Logging with FFMpegJsonCodecs {

  // https://stackoverflow.com/questions/56963790/how-to-tell-if-faststart-for-video-is-set-using-ffmpeg-or-ffprobe/56963953#56963953
  // Before avformat_find_stream_info() pos: 3193581 bytes read:3217069 seeks:0 nb_streams:2
  val fastStartPattern =
    raw"""Before\savformat_find_stream_info\(\)\spos:\s\d+\sbytes\sread:\d+\sseeks:0""".r.unanchored

  def formatTime(timestamp: Long): String = {

    val duration = Duration.ofMillis(timestamp)

    val hours   = "%02d".format(duration.toHoursPart)
    val minutes = "%02d".format(duration.toMinutesPart)
    val seconds = "%02d".format(duration.toSecondsPart)
    val millis  = "%03d".format(duration.toMillisPart)

    s"$hours:$minutes:$seconds.$millis"
  }

  def ffprobe(file: Path, debug: Boolean, timeout: FiniteDuration): Task[ProbeOutput] = {

    val fileName = file.toAbsolutePath.normalize().toString

    Task {
      val v    = if (debug) "debug" else "quiet"
      val args = List("-print_format", "json", "-show_streams", "-loglevel", v, fileName)
      run(cmds = "ffprobe" :: args)
    }.flatMap { process =>
      Task {

        val jsonOutput = scala.io.Source.fromInputStream(process.getInputStream).mkString

        // setting -v to debug will hang the standard output stream on some files.
        val debugOutput = {
          if (debug) {
            val debugOutput = scala.io.Source.fromInputStream(process.getErrorStream).mkString
            val fastStart   = fastStartPattern.matches(debugOutput)
            Some(ProbeDebugOutput(fastStart))
          } else {
            None
          }
        }

        io.circe.parser.decode[ProbeOutput](jsonOutput) match {
          case Left(error) => throw error
          case Right(out)  => out.copy(debugOutput = debugOutput)
        }
      }.doOnCancel(Task { process.destroy() })
    }.timeout(timeout)
  }

  def addFastStart(video: Path): Path = {

    val out = s"${video.stripExtension}-faststart.mp4"

    logger.info(s"Adding faststart at: $out")

    // format: off
    runSync(
      useErrorStream = true,
      cmds = List(
        "ffmpeg",
        "-i",        video.absoluteFileName(),
        "-c",        "copy",
        "-map",      "0",
        "-movflags", "+faststart",
        "-y",        out
      )
    )
    // format: on

    Path.of(out)
  }

  def copyMp4(
      inputFile: Path,
      start: Long,
      end: Long,
      outputFile: Option[Path] = None
  ): Unit = {
    val input  = inputFile.absoluteFileName()
    val output = outputFile.map(_.absoluteFileName()).getOrElse(s"${stripExtension(input)}.mp4")

    // format: off
    val args = List(
        "-ss",       formatTime(start),
        "-to",       formatTime(end),
        "-i",        input,
        "-c",        "copy",
        "-map",      "0",
        "-movflags", "+faststart",
        "-y",        output
      )
    // format: on

    runSync(useErrorStream = true, cmds = "ffmpeg" :: args)
  }

  def transcodeToMp4(
      inputFile: Path,
      range: (Long, Long),
      outputFile: Option[Path] = None,
      quality: Int = 24,
      scaleHeight: Option[Int]
  ): Unit = {

    val (ss, to) = range
    val input    = inputFile.absoluteFileName()
    val output   = outputFile.map(_.absoluteFileName()).getOrElse(s"${stripExtension(input)}.mp4")

    // format: off
    val args: List[String] =
      List(
        "-ss",  formatTime(ss),
        "-to",  formatTime(to),
        "-i",   input,
      ) ++
        scaleHeight.toList.flatMap(height => List("-vf",  s"scale=-2:$height")) ++
      List(
        "-movflags", "+faststart",
        "-crf", s"$quality",
        "-an", // no audio
        "-v",   "quiet",
        "-y",   output
      )
    // format: on

    runSync(useErrorStream = true, cmds = "ffmpeg" :: args)
  }

  def streamFragment(
      inputFile: String,
      from: Long,
      to: Long,
      quality: Int = 23,
      scaleHeight: Option[Int] = None
  ): InputStream = {

    // format: off
    val args: List[String] =
    List(
      "-ss",  formatTime(from),
      "-to",  formatTime(to),
      "-i",   inputFile,
    ) ++
      scaleHeight.toList.flatMap(height => List("-vf",  s"scale=-2:$height")) ++
      List(
        "-c:v", "libx264",
        "-movflags", "+faststart+frag_keyframe+empty_moov",
        "-crf", s"$quality",
        "-f", "mp4",
        "-an", // no audio
        "pipe:1"
      )
    // format: on

    run("ffmpeg" :: args).getInputStream
  }

  def streamThumbnail(
      inputFile: Path,
      timestamp: Long,
      scaleHeight: Int
  ): InputStream = {

    // format: off
    val args = List(
      "-ss",      formatTime(timestamp),
      "-i" ,      inputFile.toString,
      "-vcodec",  "webp",
      "-vf",      s"scale=-2:$scaleHeight",
      "-vframes", "1",
      "-f",       "image2pipe",
      "-"
    )
    // format: on

    run("ffmpeg" :: args).getInputStream
  }

  def writeThumbnail(
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
          s"Failed to create thumbnail for inputFile: ${inputFile}, timestamp: ${formatTime(timestamp)}, outputFile: ${outputFile}, scaleHeight: ${scaleHeight}"
        )
    }
  }

  def runSync(useErrorStream: Boolean, cmds: Seq[String]): String = {

    logger.debug(s"Running command: ${cmds.mkString(",")}")

    val process  = Runtime.getRuntime.exec(cmds.toArray)
    val is       = if (useErrorStream) process.getErrorStream else process.getInputStream
    val output   = scala.io.Source.fromInputStream(is).mkString
    val exitCode = process.waitFor()

    if (exitCode != 0)
      logger.warn(s"""Non zero exit code for command: ${cmds.mkString(",")} \n""" + output)

    output
  }

  def run(cmds: Seq[String]): Process = {
    Runtime.getRuntime.exec(cmds.toArray)
  }
}
